#!/usr/bin/env python3
"""Android example E2E driver and screenshot helpers.

Subcommands:
  run-shard
      Run one shard's share of the waterui repo's examples on the attached
      emulator: launch each with `water run`, wait for the real framebuffer
      signal — two consecutive byte-identical captures — then compare against
      e2e/goldens (verify) or assert non-blank content (smoke).
      --golden-mode=record captures verify-mode frames into
      <artifacts-dir>/candidates/ instead of comparing.
  nonblank <png>
      Exit 1 when the screenshot is effectively a single flat color.
  compare <golden> <actual> <tolerance> <max-fraction> <diff-out>
      Exit 1 when the fraction of pixels whose per-channel difference
      exceeds <tolerance> is greater than <max-fraction>. Writes an
      amplified diff image to <diff-out> on mismatch.
"""

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time
from io import BytesIO
from pathlib import Path

# A settled screen of real content shows a stddev far above this; a flat
# fill — the failure mode "the app opened but rendered nothing" produces —
# sits near zero.
BLANK_STDDEV = 4.0
DOWNSCALE = (64, 64)
# Frames must hold identical+non-blank this long to count as settled — outlives
# transient system overlays like the fading scrollbar.
MIN_STABLE_S = 2.0
# System-rendered chrome excluded from golden comparison: the status bar icons
# depend on whether the demo-mode broadcast landed, and the gesture bar is not
# app content. Fractions of height, so any display size works.
CROP_TOP = 0.04
CROP_BOTTOM = 0.02

# Compose MD3 parity: the reference app renders the registered twin for an
# example, the driver pixel-compares it against the WaterUI capture. Twins are
# listed in e2e/parity-budgets.json; per-example entries override the default
# allowed diff fraction (mirrors the Apple parity harness).
REFERENCE_PACKAGE = "dev.waterui.android.reference"
REFERENCE_ACTIVITY = f"{REFERENCE_PACKAGE}/.MainActivity"
PARITY_DEFAULT = 0.02

# `water run` prints this once the app is up; it is the only startup signal
# the CLI emits that is reliably post-launch rather than post-build.
STARTUP_SIGNAL = "Application started"
# A cold Rust build of a dependency-heavy example can legitimately run for
# tens of minutes while emitting nothing — a single fat crate's compile
# produces no log lines at all — so "stalled" is judged by CPU, not output:
# the run's whole process group going idle means it is genuinely wedged.
STARTUP_IDLE_S = 300
STARTUP_CAP_S = 2400

# Freeze the status bar so screenshots compare run to run: demo mode pins
# the clock, fixes wifi/battery, and hides notification icons. Entered once
# per emulator; the setting persists until the device reboots.
DEMO_MODE_COMMANDS = (
    ("shell", "settings", "put", "global", "sysui_demo_allowed", "1"),
    ("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "enter"),
    ("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "clock",
     "-e", "hhmm", "1200"),
    ("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "network",
     "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true"),
    ("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "mobile",
     "-e", "show", "false"),
    ("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "battery",
     "-e", "level", "100", "-e", "plugged", "false"),
    ("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command",
     "notifications", "-e", "visible", "false"),
)


# ---------- image predicates ----------


# Emulator arch → Rust target triple, the mapping `rustup target add` needs.
RUST_TARGET_FOR_ARCH = {
    "x86_64": "x86_64-linux-android",
    "x86": "i686-linux-android",
    "arm64-v8a": "aarch64-linux-android",
    "armeabi-v7a": "armv7-linux-androideabi",
}

# `water run` gates on the toolchain check for every ABI it could target, not
# just the selected device's, so a runner needs all four triples installed.
ALL_RUST_TARGETS = tuple(dict.fromkeys(RUST_TARGET_FOR_ARCH.values()))


def is_nonblank(png: bytes) -> bool:
    from PIL import Image, ImageStat

    try:
        image = Image.open(BytesIO(png)).convert("L").resize(DOWNSCALE)
    except Exception:
        return False
    return ImageStat.Stat(image).stddev[0] >= BLANK_STDDEV


def compare_images(
    golden_path: Path, actual_path: Path, tolerance: int, max_fraction: float, diff_out: Path
) -> int:
    from PIL import Image, ImageChops

    golden = Image.open(golden_path).convert("RGB")
    actual = Image.open(actual_path).convert("RGB")
    if golden.size != actual.size:
        print(
            f"compare: size mismatch golden={golden.size} actual={actual.size}",
            file=sys.stderr,
        )
        return 2
    w, h = golden.size
    box = (0, int(h * CROP_TOP), w, h - int(h * CROP_BOTTOM))
    golden = golden.crop(box)
    actual = actual.crop(box)

    diff = ImageChops.difference(golden, actual)
    masks = [band.point(lambda v: 255 if v > tolerance else 0) for band in diff.split()]
    mask = masks[0]
    for other in masks[1:]:
        mask = ImageChops.lighter(mask, other)

    differing = mask.histogram()[255]
    total = golden.size[0] * golden.size[1]
    fraction = differing / total
    print(
        f"compare: {differing}/{total} pixels differ beyond tolerance {tolerance} "
        f"({fraction:.4%}, max {max_fraction:.4%})"
    )

    if fraction > max_fraction:
        diff.point(lambda v: min(255, v * 8)).save(diff_out)
        return 1
    return 0


# ---------- adb ----------


def detect_serial() -> str:
    out = subprocess.run(
        ["adb", "devices"], check=True, capture_output=True, text=True
    ).stdout
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[0].startswith("emulator-") and parts[1] == "device":
            return parts[0]
    sys.exit("No Android emulator/device detected and ANDROID_SERIAL is unset.")


def adb(serial: str, *args: str) -> None:
    subprocess.run(["adb", "-s", serial, *args], check=True)


def adb_out(serial: str, *args: str) -> bytes:
    return subprocess.run(["adb", "-s", serial, *args], check=True, capture_output=True).stdout


class EmulatorLostError(Exception):
    """The emulator/device vanished mid-shard; no example can proceed."""


def device_alive(serial: str) -> bool:
    result = subprocess.run(
        ["adb", "-s", serial, "get-state"], capture_output=True, text=True
    )
    return result.returncode == 0 and result.stdout.strip() == "device"


def capture_screen(serial: str) -> bytes:
    # exec-out keeps the PNG binary-safe; `adb shell screencap` runs through a
    # pty that can corrupt bytes on some devices. A failed capture is empty
    # bytes so callers keep polling — unless the device itself is gone, which
    # no amount of polling fixes.
    try:
        return adb_out(serial, "exec-out", "screencap", "-p")
    except subprocess.CalledProcessError:
        if not device_alive(serial):
            raise EmulatorLostError(serial)
        return b""


def enter_demo_mode(serial: str) -> None:
    try:
        for args in DEMO_MODE_COMMANDS:
            adb(serial, *args)
    except subprocess.CalledProcessError:
        if not device_alive(serial):
            raise EmulatorLostError(serial)
        # Demo mode only stabilizes the status bar in screenshots; a rejected
        # command on a live device is not worth failing the shard over.
        print(f"::warning::demo mode not fully enabled on {serial}")


# `water run` reports process death with this line; the VM's own markers ride
# in the same stream when the runtime logs through it.
CRASH_MARKERS = ("Application crashed", "FATAL EXCEPTION", "Fatal signal")
# Logcat snapshot size kept per failed example — enough for the FATAL block
# plus context, small enough to stay artifact-friendly.
LOGCAT_TAIL_LINES = 4000


def crash_reason(proc: subprocess.Popen, log_file: Path) -> str | None:
    """Why the example can never settle: the run died, or its log shows the
    app process crashed. Checked once per framebuffer poll so a dead app
    fails fast instead of burning the whole settle window."""
    code = proc.poll()
    if code is not None:
        return f"water run exited ({code})"
    try:
        with open(log_file, "rb") as handle:
            handle.seek(0, os.SEEK_END)
            handle.seek(max(0, handle.tell() - 65536))
            tail = handle.read().decode("utf-8", "replace")
    except OSError:
        return None
    for marker in CRASH_MARKERS:
        if marker in tail:
            return f"run log shows '{marker}'"
    return None


def dump_meminfo(serial: str, example_path: Path, out_path: Path) -> None:
    """Per-example `dumpsys meminfo` snapshot for the nightly artifact bundle.

    The application id lives in the example's Water.toml rather than following
    a naming rule (`dev.waterui.edge_list`, `com.waterui.example.drop_and_drop`),
    so it is read out of the manifest instead of derived.
    """
    water_toml = example_path / "Water.toml"
    try:
        match = re.search(
            r'^\s*bundle_identifier\s*=\s*"([^"]+)"', water_toml.read_text(),
            re.MULTILINE,
        )
    except OSError:
        return
    if not match:
        return
    try:
        data = adb_out(serial, "shell", "dumpsys", "meminfo", match.group(1))
    except subprocess.CalledProcessError:
        return
    out_path.write_bytes(data)


def dump_logcat(serial: str, out_path: Path) -> None:
    """Full-buffer logcat for a failed example — the FATAL block lives in the
    crash buffer, which a pid-filtered dump can miss once the process is gone."""
    try:
        data = adb_out(serial, "logcat", "-d", "-b", "all", "-t", str(LOGCAT_TAIL_LINES))
    except subprocess.CalledProcessError:
        return
    if data:
        out_path.write_bytes(data)


# ---------- readiness waits ----------


def _process_group_cpu_seconds(pgid: int) -> float:
    """Cumulative CPU time of every process in the group, via ps — portable
    across the Linux CI runners and local macOS runs (/proc is Linux-only)."""
    out = subprocess.run(
        ["ps", "-o", "time=", "-g", str(pgid)], capture_output=True, text=True
    ).stdout
    total = 0.0
    for line in out.splitlines():
        # ps prints [[DD-]HH:]MM:SS[.cc]
        head, sep, tail = line.strip().partition("-")
        if not head:
            continue
        timestr, days = (tail, int(head)) if sep else (head, 0)
        parts = timestr.split(":")
        seconds = float(parts[-1]) + int(parts[-2]) * 60
        if len(parts) == 3:
            seconds += int(parts[0]) * 3600
        seconds += days * 86400
        total += seconds
    return total


def wait_for_start(proc: subprocess.Popen, log_file: Path) -> bool:
    cap = time.monotonic() + STARTUP_CAP_S
    last_cpu = -1.0
    last_active = time.monotonic()
    while True:
        if proc.poll() is not None:
            print("run process exited before the startup signal.", file=sys.stderr)
            return False
        if log_file.exists() and STARTUP_SIGNAL in log_file.read_text(errors="replace"):
            return True
        cpu = _process_group_cpu_seconds(proc.pid)
        if cpu != last_cpu:
            last_cpu = cpu
            last_active = time.monotonic()
        elif time.monotonic() - last_active > STARTUP_IDLE_S:
            print(
                f"run went idle for {STARTUP_IDLE_S}s without the startup signal.",
                file=sys.stderr,
            )
            return False
        if time.monotonic() > cap:
            print(f"startup wait hit the {STARTUP_CAP_S}s cap.", file=sys.stderr)
            return False
        time.sleep(5)


def wait_for_settle(
    serial: str,
    timeout_s: float,
    poll_s: float,
    abort_check=None,
) -> tuple[bool, bytes | None]:
    """Poll the framebuffer until captures stay byte-identical AND non-blank
    for MIN_STABLE_S — the "the app finished drawing something" signal.
    Single identical pairs are not enough: an app can hold an empty background
    before content arrives, or hold a transient overlay (a scrollbar mid-fade)
    static for one poll interval. `abort_check` returning a reason string
    stops the wait early — a crashed app can never settle. Returns
    (settled, last frame) either way."""
    deadline = time.monotonic() + timeout_s
    prev: bytes | None = None
    cur: bytes | None = None
    stable_since: float | None = None
    while time.monotonic() < deadline:
        if abort_check is not None and abort_check() is not None:
            return False, cur
        cur = capture_screen(serial)
        if cur and cur == prev and is_nonblank(cur):
            if stable_since is None:
                stable_since = time.monotonic()
            if time.monotonic() - stable_since >= MIN_STABLE_S:
                return True, cur
        else:
            stable_since = None
        prev = cur
        time.sleep(poll_s)
    return False, cur


def wait_for_content(
    serial: str,
    timeout_s: float,
    poll_s: float,
    abort_check=None,
) -> bytes | None:
    """A smoke-mode example animates forever by definition, so "non-blank
    content on screen" is the assertion. Poll captures until content appears;
    a frame that stays flat past the deadline is the failure this exists to
    catch. `abort_check` stops the wait early on app death."""
    deadline = time.monotonic() + timeout_s
    frame = b""
    while time.monotonic() < deadline:
        if abort_check is not None and abort_check() is not None:
            return None
        frame = capture_screen(serial)
        if frame and is_nonblank(frame):
            return frame
        time.sleep(poll_s)
    return None


def capture_twin(serial: str, example: str, timeout_s: float, poll_s: float) -> bytes | None:
    """Launch the Compose MD3 reference host for `example` and return its
    settled screenshot. The reference activity is force-stopped afterwards so
    the next launch starts cold."""
    adb(serial, "shell", "am", "start", "-W", "-n", REFERENCE_ACTIVITY,
        "--es", "E2EExample", example)
    try:
        _, frame = wait_for_settle(serial, timeout_s, poll_s)
        return frame
    finally:
        subprocess.run(
            ["adb", "-s", serial, "shell", "am", "force-stop", REFERENCE_PACKAGE],
            capture_output=True,
        )


def verify_parity(
    example: str,
    actual: Path,
    serial: str,
    cfg: dict,
    budget: float,
    artifacts_dir: Path,
) -> bool:
    """Render the registered Compose twin and pixel-compare it against the
    WaterUI capture. Returns False when the twin cannot render or the diff
    exceeds the example's parity budget."""
    twin_png = artifacts_dir / f"{example}.twin.png"
    frame = capture_twin(serial, example, cfg["settle_s"], cfg["poll_s"])
    if not frame:
        print(f"parity: twin for {example} produced no frame", file=sys.stderr)
        return False
    twin_png.write_bytes(frame)
    result = compare_images(
        twin_png, actual, cfg["tolerance"], budget,
        artifacts_dir / f"{example}.parity.diff.png",
    )
    if result == 2:
        return False
    if result != 0:
        print(
            f"parity: {example} diverges from its Compose twin beyond budget "
            f"{budget:.4%} — see {example}.parity.diff.png",
            file=sys.stderr,
        )
        return False
    return True


def stop_run(proc: subprocess.Popen) -> None:
    """Ctrl-C semantics for the whole `water run` tree: it was spawned in its
    own process group, so the signal reaches the gradle/adb children too."""
    if proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGINT)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 20
    while proc.poll() is None and time.monotonic() < deadline:
        time.sleep(1)
    if proc.poll() is None:
        try:
            os.killpg(proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        proc.wait()


# ---------- shard driver ----------


def load_manifest(manifest_path: Path) -> dict:
    with open(manifest_path, encoding="utf-8") as handle:
        return json.load(handle)


def example_config(manifest: dict, example: str) -> dict:
    defaults = manifest.get("defaults", {})
    entry = manifest.get("examples", {}).get(example, {})
    return {
        "mode": entry.get("mode", "verify"),
        "reason": entry.get("reason", ""),
        "settle_s": float(entry.get("settle_timeout_seconds",
                                    defaults.get("settle_timeout_seconds", 25))),
        "poll_s": float(entry.get("poll_interval_ms",
                                  defaults.get("poll_interval_ms", 400))) / 1000,
        "tolerance": int(entry.get("pixel_tolerance",
                                   defaults.get("pixel_tolerance", 8))),
        "max_fraction": float(entry.get("max_diff_fraction",
                                        defaults.get("max_diff_fraction", 0.005))),
        # Environment forwarded to the app through `water run --env KEY=VALUE`.
        # An example whose workload exceeds emulator capacity tunes itself down
        # here rather than being skipped.
        "env": dict(entry.get("env", {})),
    }


def run_example(
    example: str,
    cfg: dict,
    serial: str,
    repo_root: Path,
    example_path: Path,
    log_file: Path,
    golden_mode: str,
    goldens_dir: Path,
    artifacts_dir: Path,
    candidates_dir: Path,
    results: list,
    parity_budget: float | None,
) -> bool:
    if cfg["mode"] == "skip":
        print(f"Skipping {example}: {cfg['reason'] or 'no reason given'}")
        results.append((example, "SKIP", cfg["reason"]))
        return True

    print(f"::group::android-e2e:{example} (mode={cfg['mode']})", flush=True)
    command = ["water", "run", "--platform", "android", "--device", serial,
               "--path", str(example_path)]
    for key, value in cfg["env"].items():
        command += ["--env", f"{key}={value}"]
    with open(log_file, "wb") as log:
        proc = subprocess.Popen(
            command,
            cwd=repo_root,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            return _run_started_example(
                example, cfg, proc, log_file, serial,
                golden_mode, goldens_dir, artifacts_dir, candidates_dir, results,
                parity_budget, example_path,
            )
        finally:
            stop_run(proc)
            print("::endgroup::", flush=True)


def _run_started_example(
    example: str,
    cfg: dict,
    proc: subprocess.Popen,
    log_file: Path,
    serial: str,
    golden_mode: str,
    goldens_dir: Path,
    artifacts_dir: Path,
    candidates_dir: Path,
    results: list,
    parity_budget: float | None,
    example_path: Path,
) -> bool:
    actual = artifacts_dir / f"{example}.actual.png"
    crashed = lambda: crash_reason(proc, log_file)

    if not wait_for_start(proc, log_file):
        # Whatever is on screen right now — a crash dialog, the installer
        # error, a black flash — is exactly the diagnostic this artifact is for.
        frame = capture_screen(serial)
        if frame:
            actual.write_bytes(frame)
        dump_meminfo(serial, example_path, artifacts_dir / f"{example}.meminfo.txt")
        dump_logcat(serial, artifacts_dir / f"{example}.logcat.txt")
        print(f"::error::Example {example} failed to start.")
        print("\n".join(log_file.read_text(errors="replace").splitlines()[-200:]))
        results.append((example, "FAIL", "failed to start"))
        return False

    status = "PASS"
    abort = crashed()

    if cfg["mode"] == "verify":
        settled, frame = wait_for_settle(
            serial, cfg["settle_s"], cfg["poll_s"], abort_check=crashed
        )
        abort = abort or crashed()
        detail = "settled" if settled else (
            f"{abort}, compared the final frame" if abort else
            f"no settled frame within {cfg['settle_s']:g}s; compared the final frame"
        )
        if not frame:
            detail += "; no framebuffer capture at all"
            status = "FAIL"
        else:
            actual.write_bytes(frame)
            if not is_nonblank(frame):
                status = "FAIL"
                detail += "; screen stayed blank"
            elif not verify_golden(
                example, actual, cfg, golden_mode, goldens_dir, artifacts_dir
            ):
                status = "FAIL"
            elif parity_budget is not None and golden_mode != "record":
                if not verify_parity(
                    example, actual, serial, cfg, parity_budget, artifacts_dir
                ):
                    status = "FAIL"
                    detail += "; diverges from Compose twin"
        if abort and status != "FAIL":
            status = "FAIL"
    else:  # smoke
        frame = wait_for_content(
            serial, cfg["settle_s"], cfg["poll_s"], abort_check=crashed
        )
        abort = abort or crashed()
        if frame is None:
            status = "FAIL"
            detail = abort or f"screen stayed blank for {cfg['settle_s']:g}s after startup"
            last = capture_screen(serial)
            if last:
                actual.write_bytes(last)
        else:
            actual.write_bytes(frame)
            detail = "non-blank content on screen"

    # Memory footprint rides every example, pass or fail.
    dump_meminfo(serial, example_path, artifacts_dir / f"{example}.meminfo.txt")

    if status == "FAIL":
        dump_logcat(serial, artifacts_dir / f"{example}.logcat.txt")
        if proc.poll() is not None:
            detail += f"; water run exited with status {proc.returncode} during capture — see log"

    if golden_mode == "record" and cfg["mode"] == "verify" and status != "FAIL":
        (candidates_dir / f"{example}.png").write_bytes(actual.read_bytes())

    if status == "FAIL":
        print(f"::error::Example {example}: {detail}")
        print("\n".join(log_file.read_text(errors="replace").splitlines()[-50:]))
    else:
        print(f"Example {example}: {detail}")
    results.append((example, status, detail))
    return status != "FAIL"


def verify_golden(
    example: str,
    actual: Path,
    cfg: dict,
    golden_mode: str,
    goldens_dir: Path,
    artifacts_dir: Path,
) -> bool:
    golden = goldens_dir / f"{example}.png"
    diff_out = artifacts_dir / f"{example}.diff.png"

    if not golden.is_file():
        if golden_mode == "record":
            return True
        print(
            f"no golden at e2e/goldens/{example}.png — run the nightly workflow with "
            "golden_mode=record and commit the candidates",
            file=sys.stderr,
        )
        return False

    result = compare_images(
        golden, actual, cfg["tolerance"], cfg["max_fraction"], diff_out
    )
    if golden_mode == "record":
        return True
    return result == 0


def cmd_run_shard(args: argparse.Namespace) -> int:
    repo_root = Path(args.repo_root).resolve()
    backend_dir = repo_root / "backends" / "android"
    examples_root = repo_root / "examples"

    log_dir = Path(args.log_dir or backend_dir / "ci-logs")
    manifest_path = Path(args.manifest or backend_dir / "e2e" / "manifest.json")
    goldens_dir = Path(args.goldens_dir or backend_dir / "e2e" / "goldens")
    artifacts_dir = Path(args.artifacts_dir or backend_dir / "ci-artifacts")
    candidates_dir = artifacts_dir / "candidates"
    for directory in (log_dir, artifacts_dir, candidates_dir):
        directory.mkdir(parents=True, exist_ok=True)

    if not manifest_path.is_file():
        sys.exit(f"Example manifest not found: {manifest_path}")
    if not examples_root.is_dir():
        sys.exit(f"Examples directory not found: {examples_root}")
    for tool in ("water", "adb"):
        if subprocess.run(["which", tool], capture_output=True).returncode != 0:
            sys.exit(f"{tool} not found in PATH.")

    serial = os.environ.get("ANDROID_SERIAL") or detect_serial()

    # Runnable examples: directories carrying a src/lib.rs, sorted once and
    # split round-robin across shards.
    examples = sorted(
        entry.name
        for entry in examples_root.iterdir()
        if entry.is_dir() and (entry / "src" / "lib.rs").is_file()
    )
    if not examples:
        sys.exit(f"No runnable examples found under {examples_root}")
    assigned = [
        name for index, name in enumerate(examples)
        if index % args.shard_total == args.shard_index
    ]
    if not assigned:
        print(f"Shard {args.shard_index}/{args.shard_total} has no examples assigned.")
        return 0

    print(
        f"Shard {args.shard_index}/{args.shard_total} running {len(assigned)} "
        f"examples on {serial} (golden-mode={args.golden_mode})"
    )
    print(f"Assigned examples: {' '.join(assigned)}")

    manifest = load_manifest(manifest_path)

    # Compose MD3 parity: twins registered in parity-budgets.json get a
    # pixel-compare against the reference host after the golden check. Only
    # active when the workflow built and passed --reference-apk.
    parity_budgets: dict[str, float] = {}
    if args.reference_apk:
        budgets_path = backend_dir / "e2e" / "parity-budgets.json"
        if budgets_path.is_file():
            budgets = json.loads(budgets_path.read_text())
            parity_budgets = {
                name: float(budgets.get("examples", {}).get(name, budgets.get("default", PARITY_DEFAULT)))
                for name in budgets.get("examples", {})
            }
        if parity_budgets:
            adb(serial, "install", "-r", args.reference_apk)
        else:
            print("::warning::--reference-apk given but no twins are registered in parity-budgets.json")

    results: list[tuple[str, str, str]] = []
    failures: list[str] = []
    try:
        enter_demo_mode(serial)
        for example in assigned:
            try:
                ok = run_example(
                    example,
                    example_config(manifest, example),
                    serial,
                    repo_root,
                    examples_root / example,
                    log_dir / f"{example}.log",
                    args.golden_mode,
                    goldens_dir,
                    artifacts_dir,
                    candidates_dir,
                    results,
                    parity_budgets.get(example),
                )
            except EmulatorLostError:
                results.append((example, "FAIL", f"emulator {serial} lost mid-run"))
                failures.append(example)
                raise
            if not ok:
                failures.append(example)
    except EmulatorLostError:
        # Nothing downstream of a dead emulator can run; account for every
        # un-attempted example so the results table tells the whole story.
        for example in assigned:
            if all(name != example for name, _, _ in results):
                results.append((example, "SKIP", f"emulator {serial} lost"))
        print(f"Emulator {serial} is no longer reachable.", file=sys.stderr)

    print(f"---- shard {args.shard_index}/{args.shard_total} results ----", flush=True)
    width = max(len(name) for name, _, _ in results)
    for name, status, detail in results:
        print(f"{name:<{width}}  {status:<5}  {detail}", flush=True)

    if failures:
        print(f"Failed examples: {' '.join(failures)}", file=sys.stderr)
        return 1
    return 0


def cmd_nonblank(path: str) -> int:
    from PIL import Image, ImageStat

    data = Path(path).read_bytes()
    image = Image.open(BytesIO(data)).convert("L").resize(DOWNSCALE)
    stddev = ImageStat.Stat(image).stddev[0]
    print(f"nonblank: luminance stddev {stddev:.2f} (min {BLANK_STDDEV})")
    if stddev < BLANK_STDDEV:
        print("nonblank: screen is effectively a flat color", file=sys.stderr)
        return 1
    return 0


def cmd_compare(args: argparse.Namespace) -> int:
    return compare_images(
        Path(args.golden), Path(args.actual),
        args.tolerance, args.max_fraction, Path(args.diff_out),
    )


def cmd_rust_target(args: argparse.Namespace) -> int:
    if args.arch == "all":
        print(" ".join(ALL_RUST_TARGETS))
    else:
        print(RUST_TARGET_FOR_ARCH[args.arch])
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    shard = sub.add_parser("run-shard", help="run this shard's share of the examples")
    shard.add_argument("--repo-root", required=True,
                       help="path to the checked-out waterui repository")
    shard.add_argument("--shard-index", required=True, type=int)
    shard.add_argument("--shard-total", required=True, type=int)
    shard.add_argument("--golden-mode", choices=("enforce", "record"), default="enforce")
    shard.add_argument("--log-dir")
    shard.add_argument("--manifest")
    shard.add_argument("--goldens-dir")
    shard.add_argument("--artifacts-dir")
    shard.add_argument("--reference-apk",
                       help="Compose MD3 reference APK; enables twin parity checks")
    shard.set_defaults(func=cmd_run_shard)

    nonblank = sub.add_parser("nonblank", help="reject a flat-color screenshot")
    nonblank.add_argument("png")
    nonblank.set_defaults(func=lambda a: cmd_nonblank(a.png))

    compare = sub.add_parser("compare", help="compare a screenshot to its golden")
    compare.add_argument("golden")
    compare.add_argument("actual")
    compare.add_argument("tolerance", type=int)
    compare.add_argument("max_fraction", type=float)
    compare.add_argument("diff_out")
    compare.set_defaults(func=cmd_compare)

    rt = sub.add_parser(
        "rust-target",
        help="print the Rust target triples for an emulator arch, or 'all'",
    )
    rt.add_argument("arch", choices=[*sorted(RUST_TARGET_FOR_ARCH), "all"])
    rt.set_defaults(func=cmd_rust_target)

    return parser


def main() -> int:
    args = build_parser().parse_args()
    if getattr(args, "shard_total", 1) <= 0:
        sys.exit("--shard-total must be > 0")
    if hasattr(args, "shard_index") and not (0 <= args.shard_index < args.shard_total):
        sys.exit("--shard-index must be within [0, shard-total)")
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
