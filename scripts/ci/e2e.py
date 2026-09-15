#!/usr/bin/env python3
"""Android example E2E driver and screenshot helpers.

Subcommands:
  run-shard
      Run one shard's share of the waterui repo's examples on the attached
      emulator: `water package --release` each example, sign and install the
      APK, then launch it and wait for the real framebuffer signal — two
      consecutive byte-identical captures — then compare against
      e2e/goldens (verify) or assert non-blank content (smoke). Package
      size, cold-start, and memory metrics ride every example — measuring
      the release build is the point; a debug `water run` build is not the
      artifact users ship.
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
import subprocess
import sys
import time
from io import BytesIO
from pathlib import Path

# sys.path[0] is this script's directory; the release-APK discovery and the
# size breakdown live in release_metrics.py so the standalone tool and the
# shard driver report the same numbers.
from release_metrics import apk_breakdown, find_release_apk

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

# A cold release build of a dependency-heavy example can legitimately run for
# tens of minutes; beyond this the package step is treated as wedged.
PACKAGE_TIMEOUT_S = 2400

# Emulator CPU ABI (`ro.product.cpu.abi`) → the `water --arch` spelling, from
# the CLI's TargetArch value enum.
CLI_ARCH_FOR_ABI = {
    "x86_64": "x86-64",
    "x86": "x86",
    "arm64-v8a": "arm64",
    "armeabi-v7a": "armv7",
}

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


# Logcat snapshot size kept per failed example — enough for the FATAL block
# plus context, small enough to stay artifact-friendly.
LOGCAT_TAIL_LINES = 4000


class ProcessWatch:
    """`pidof` liveness for a launched package. `am start` returns before the
    fork lands, so "not seen yet" is not a death — but a captured frame is
    only evidence while the process is actually alive: a process that dies
    before the first poll leaves a non-blank crash dialog on screen, which
    must not count toward settle/content."""
    def __init__(self, serial: str, package: str):
        self.serial = serial
        self.package = package
        self.seen = False

    def poll(self) -> bool:
        try:
            alive = bool(
                adb_out(self.serial, "shell", "pidof", self.package).strip()
            )
        except subprocess.CalledProcessError:
            alive = False
        self.seen = self.seen or alive
        return alive

    def abort_reason(self) -> str | None:
        return (
            f"app process {self.package} exited"
            if self.seen and not self.poll()
            else None
        )


def bundle_id(example_path: Path) -> str | None:
    """The application id lives in the example's Water.toml rather than
    following a naming rule (`dev.waterui.edge_list`,
    `com.waterui.example.drop_and_drop`), so it is read out of the manifest
    instead of derived."""
    try:
        match = re.search(
            r'^\s*bundle_identifier\s*=\s*"([^"]+)"',
            (example_path / "Water.toml").read_text(),
            re.MULTILINE,
        )
    except OSError:
        return None
    return match.group(1) if match else None


def parse_meminfo(data: bytes) -> dict:
    """Pull the headline numbers out of `dumpsys meminfo` so the nightly can
    trend them instead of archiving raw text. Values are kilobytes."""
    text = data.decode("utf-8", "replace")
    metrics: dict = {}
    match = re.search(
        r"TOTAL PSS:\s*(\d+)\s+TOTAL RSS:\s*(\d+)\s+TOTAL SWAP PSS:\s*(\d+)",
        text,
    )
    if match:
        metrics["total_pss_kb"] = int(match.group(1))
        metrics["total_rss_kb"] = int(match.group(2))
        metrics["swap_pss_kb"] = int(match.group(3))
    for key, label in (("java_heap_pss_kb", "Java Heap"),
                       ("native_heap_pss_kb", "Native Heap")):
        match = re.search(rf"^\s*{label}:\s*(\d+)", text, re.MULTILINE)
        if match:
            metrics[key] = int(match.group(1))
    return metrics


def dump_meminfo(serial: str, example_path: Path, out_path: Path) -> dict:
    """Per-example `dumpsys meminfo` snapshot for the nightly artifact bundle.
    Returns the parsed metrics (empty dict when unavailable)."""
    package = bundle_id(example_path)
    if not package:
        return {}
    try:
        data = adb_out(serial, "shell", "dumpsys", "meminfo", package)
    except subprocess.CalledProcessError:
        return {}
    out_path.write_bytes(data)
    return parse_meminfo(data)


def displayed_time_ms(serial: str, example_path: Path) -> int | None:
    """Time-to-initial-display from the ActivityTaskManager `Displayed` logcat
    line — the canonical cold-start number Android itself reports. The buffer
    may hold launches from earlier examples, so the last match wins."""
    package = bundle_id(example_path)
    if not package:
        return None
    try:
        data = adb_out(
            serial, "logcat", "-d", "-b", "main", "-s", "ActivityTaskManager"
        ).decode("utf-8", "replace")
    except subprocess.CalledProcessError:
        return None
    displayed = None
    for match in re.finditer(rf"Displayed {re.escape(package)}[^\n]*", data):
        seconds = re.search(r"\+(\d+)s(\d+)ms", match.group(0))
        millis = re.search(r"\+(\d+)ms", match.group(0))
        if seconds:
            displayed = int(seconds.group(1)) * 1000 + int(seconds.group(2))
        elif millis:
            displayed = int(millis.group(1))
    return displayed


def write_metrics(
    artifacts_dir: Path, example: str, cfg: dict, status: str, metrics: dict
) -> None:
    """One small JSON per example — the shard merges them into
    `metrics-shard-N.json`, and the nightly's aggregation job merges the
    shards into `nightly-metrics.json` for the run summary."""
    payload = {
        "example": example,
        "mode": cfg["mode"],
        "status": status,
        **metrics,
    }
    (artifacts_dir / f"{example}.metrics.json").write_text(
        json.dumps(payload, indent=2) + "\n"
    )


def dump_logcat(serial: str, out_path: Path) -> None:
    """Full-buffer logcat for a failed example — the FATAL block lives in the
    crash buffer, which a pid-filtered dump can miss once the process is gone."""
    try:
        data = adb_out(serial, "logcat", "-d", "-b", "all", "-t", str(LOGCAT_TAIL_LINES))
    except subprocess.CalledProcessError:
        return
    if data:
        out_path.write_bytes(data)


# ---------- package, sign, install, launch ----------


def device_abi(serial: str) -> str:
    abi = adb_out(serial, "shell", "getprop", "ro.product.cpu.abi").decode().strip()
    if abi not in CLI_ARCH_FOR_ABI:
        sys.exit(f"emulator {serial} reports unsupported ABI {abi!r}")
    return abi


def package_release(
    repo_root: Path, example_path: Path, arch: str, log_file: Path
) -> tuple[Path, int]:
    """`water package --release` the example. Returns (unsigned apk, wall ms).
    Raises RuntimeError with the log tail on build failure."""
    started = time.time()
    with open(log_file, "wb") as log:
        try:
            result = subprocess.run(
                ["water", "package",
                 "--platform", "android",
                 "--backend", "android",
                 "--arch", arch,
                 "--release",
                 "--path", str(example_path)],
                cwd=repo_root,
                stdout=log,
                stderr=subprocess.STDOUT,
                timeout=PACKAGE_TIMEOUT_S,
            )
        except subprocess.TimeoutExpired:
            raise RuntimeError(
                f"water package hit the {PACKAGE_TIMEOUT_S}s cap"
            ) from None
    package_ms = int((time.time() - started) * 1000)
    if result.returncode != 0:
        raise RuntimeError(f"water package --release exited {result.returncode}")
    apk = find_release_apk(
        Path.home() / ".water" / "build_cache", started - 60
    )
    if apk is None:
        raise RuntimeError(
            "water package succeeded but produced no release APK under "
            "~/.water/build_cache"
        )
    return apk, package_ms


def find_apksigner() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sys.exit("ANDROID_HOME/ANDROID_SDK_ROOT unset — cannot locate apksigner")
    candidates = sorted(
        Path(sdk).glob("build-tools/*/apksigner"),
        key=lambda path: [int(part) for part in path.parent.name.split(".")],
    )
    if not candidates:
        sys.exit(f"no apksigner under {sdk}/build-tools — install a build-tools package")
    return str(candidates[-1])


def ensure_keystore(path: Path) -> None:
    """One throwaway debug keystore per shard run — the release APK must be
    signed to install, and nothing about the suite depends on the identity."""
    if path.is_file():
        return
    subprocess.run(
        ["keytool", "-genkeypair",
         "-keystore", str(path),
         "-alias", "e2e",
         "-keyalg", "RSA",
         "-keysize", "2048",
         "-validity", "10000",
         "-storepass", "android",
         "-keypass", "android",
         "-dname", "CN=WaterUI E2E"],
        check=True,
        capture_output=True,
    )


def sign_apk(apksigner: str, keystore: Path, apk: Path) -> None:
    subprocess.run(
        [apksigner, "sign",
         "--ks", str(keystore),
         "--ks-pass", "pass:android",
         "--key-pass", "pass:android",
         str(apk)],
        check=True,
        capture_output=True,
    )


def install_apk(serial: str, apk: Path) -> str:
    return adb_out(serial, "install", "-r", str(apk)).decode("utf-8", "replace")


def launch_app(serial: str, package: str, env: dict) -> str:
    """`am start` the example's MainActivity, force-stopping any prior run.
    `water run --env` reaches the app as `waterui.env.*` string extras —
    the generated MainActivity applies them with `Os.setenv` before the
    native library loads, so the same extras carry the e2e env overrides."""
    args = ["shell", "am", "start", "-S", "-n", f"{package}/.MainActivity"]
    for key, value in env.items():
        args += ["--es", f"waterui.env.{key}", value]
    return adb_out(serial, *args).decode("utf-8", "replace")


def force_stop(serial: str, package: str) -> None:
    subprocess.run(
        ["adb", "-s", serial, "shell", "am", "force-stop", package],
        capture_output=True,
    )


def anr_dialog_package(serial: str) -> str | None:
    """Package named by a focused "Application Not Responding" window, or None.
    A launcher ANR overlay holds a static dimmed frame long enough to read as
    settled — and in record mode that polluted frame becomes a golden."""
    out = subprocess.run(
        ["adb", "-s", serial, "shell", "dumpsys", "window", "displays"],
        capture_output=True,
    ).stdout.decode("utf-8", "replace")
    m = re.search(r"Application Not Responding: ([\w.]+)", out)
    return m.group(1) if m else None


# ---------- readiness waits ----------


def wait_for_settle(
    serial: str,
    timeout_s: float,
    poll_s: float,
    watch: ProcessWatch | None = None,
    package: str | None = None,
    anr: list[str] | None = None,
) -> tuple[bool, bytes | None, float | None]:
    """Poll the framebuffer until captures stay byte-identical AND non-blank
    for MIN_STABLE_S — the "the app finished drawing something" signal.
    Single identical pairs are not enough: an app can hold an empty background
    before content arrives, or hold a transient overlay (a scrollbar mid-fade)
    static for one poll interval. With a `watch`, only frames captured while
    the process is alive count — an app that dies before first sight leaves a
    static crash dialog that would otherwise read as settled. A static frame
    is checked for an ANR dialog before counting toward stability: a foreign
    package's dialog is killed and ignored, while an ANR in `package` itself
    fails the wait early and is reported through `anr`. Returns
    (settled, last frame, monotonic timestamp of the first non-blank frame)."""
    deadline = time.monotonic() + timeout_s
    prev: bytes | None = None
    cur: bytes | None = None
    stable_since: float | None = None
    content_at: float | None = None
    while time.monotonic() < deadline:
        cur = capture_screen(serial)
        if watch is not None:
            alive = watch.poll()
            if watch.seen and not alive:
                return False, cur, content_at
            if not alive:
                prev = None
                stable_since = None
                time.sleep(poll_s)
                continue
        if content_at is None and cur and is_nonblank(cur):
            content_at = time.monotonic()
        if cur and cur == prev and is_nonblank(cur):
            anr_pkg = anr_dialog_package(serial)
            if anr_pkg and anr_pkg != package:
                adb(serial, "shell", "am", "kill", anr_pkg)
                prev = None
                stable_since = None
                continue
            if anr_pkg:
                if anr is not None:
                    anr.append(anr_pkg)
                return False, cur, content_at
            if stable_since is None:
                stable_since = time.monotonic()
            if time.monotonic() - stable_since >= MIN_STABLE_S:
                return True, cur, content_at
        else:
            stable_since = None
        prev = cur
        time.sleep(poll_s)
    return False, cur, content_at


def wait_for_content(
    serial: str,
    timeout_s: float,
    poll_s: float,
    watch: ProcessWatch | None = None,
    package: str | None = None,
    anr: list[str] | None = None,
) -> tuple[bytes | None, float | None]:
    """A smoke-mode example animates forever by definition, so "non-blank
    content on screen" is the assertion. Poll captures until content appears;
    a frame that stays flat past the deadline is the failure this exists to
    catch. With a `watch`, frames only count while the process is alive, so an
    app dead before first sight times out instead of passing on its crash
    dialog. An ANR dialog is non-blank too: a foreign package's dialog is
    killed and ignored, while an ANR in `package` itself fails the wait early
    and is reported through `anr`. Returns (frame, monotonic timestamp of the
    first non-blank capture)."""
    deadline = time.monotonic() + timeout_s
    frame = b""
    while time.monotonic() < deadline:
        frame = capture_screen(serial)
        if watch is not None:
            alive = watch.poll()
            if watch.seen and not alive:
                return None, None
            if not alive:
                time.sleep(poll_s)
                continue
        if frame and is_nonblank(frame):
            anr_pkg = anr_dialog_package(serial)
            if anr_pkg and anr_pkg != package:
                adb(serial, "shell", "am", "kill", anr_pkg)
                time.sleep(poll_s)
                continue
            if anr_pkg:
                if anr is not None:
                    anr.append(anr_pkg)
                return None, None
            return frame, time.monotonic()
        time.sleep(poll_s)
    return None, None


def capture_twin(serial: str, example: str, timeout_s: float, poll_s: float) -> bytes | None:
    """Launch the Compose MD3 reference host for `example` and return its
    settled screenshot. The reference activity is force-stopped afterwards so
    the next launch starts cold."""
    adb(serial, "shell", "am", "start", "-W", "-n", REFERENCE_ACTIVITY,
        "--es", "E2EExample", example)
    try:
        _, frame, _ = wait_for_settle(
            serial, timeout_s, poll_s, package=REFERENCE_PACKAGE
        )
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
        # Environment forwarded to the app as `waterui.env.*` intent extras —
        # the generated MainActivity applies them via `Os.setenv` before the
        # native library loads. An example whose workload exceeds emulator
        # capacity tunes itself down here rather than being skipped.
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
    arch: str,
    apksigner: str,
    keystore: Path,
) -> bool:
    if cfg["mode"] == "skip":
        print(f"Skipping {example}: {cfg['reason'] or 'no reason given'}")
        results.append((example, "SKIP", cfg["reason"]))
        return True

    print(f"::group::android-e2e:{example} (mode={cfg['mode']})", flush=True)
    try:
        return _run_packaged_example(
            example, cfg, serial, repo_root, example_path, log_file,
            golden_mode, goldens_dir, artifacts_dir, candidates_dir, results,
            parity_budget, arch, apksigner, keystore,
        )
    finally:
        print("::endgroup::", flush=True)


def _run_packaged_example(
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
    arch: str,
    apksigner: str,
    keystore: Path,
) -> bool:
    actual = artifacts_dir / f"{example}.actual.png"
    metrics: dict = {}

    def fail(detail: str) -> bool:
        # Whatever is on screen right now — a crash dialog, the installer
        # error, a black flash — is exactly the diagnostic this artifact is for.
        frame = capture_screen(serial)
        if frame:
            actual.write_bytes(frame)
        metrics.update(
            dump_meminfo(
                serial, example_path, artifacts_dir / f"{example}.meminfo.txt"
            )
        )
        dump_logcat(serial, artifacts_dir / f"{example}.logcat.txt")
        write_metrics(artifacts_dir, example, cfg, "FAIL", metrics)
        print(f"::error::Example {example}: {detail}")
        if log_file.is_file():
            print("\n".join(log_file.read_text(errors="replace").splitlines()[-100:]))
        results.append((example, "FAIL", detail))
        return False

    package = bundle_id(example_path)
    if not package:
        return fail("no bundle_identifier in the example's Water.toml")

    try:
        apk, metrics["package_ms"] = package_release(
            repo_root, example_path, arch, log_file
        )
    except RuntimeError as error:
        return fail(str(error))
    metrics["apk_bytes"] = apk.stat().st_size
    metrics.update(apk_breakdown(apk))

    try:
        sign_apk(apksigner, keystore, apk)
        # A previous install under a different signature (a debug `water run`,
        # an older e2e keystore) makes `install -r` fail with
        # INSTALL_FAILED_UPDATE_INCOMPATIBLE — uninstall first, it is cheap.
        subprocess.run(
            ["adb", "-s", serial, "shell", "pm", "uninstall", package],
            capture_output=True,
        )
        install_out = install_apk(serial, apk)
    except subprocess.CalledProcessError as error:
        return fail(f"sign/install failed: {error}")
    if "Success" not in install_out:
        return fail(f"adb install rejected the APK: {install_out.strip()}")

    launch_t = time.monotonic()
    try:
        launch_out = launch_app(serial, package, cfg["env"])
    except subprocess.CalledProcessError as error:
        return fail(f"am start failed: {error}")
    if "Error" in launch_out:
        return fail(f"am start rejected the activity: {launch_out.strip()}")

    watch = ProcessWatch(serial, package)
    status = "PASS"
    abort = watch.abort_reason()
    died: str | None = None
    settled_at_ms: int | None = None
    try:
        if cfg["mode"] == "verify":
            anr_pkgs: list[str] = []
            settled, frame, content_at = wait_for_settle(
                serial, cfg["settle_s"], cfg["poll_s"], watch=watch,
                package=package, anr=anr_pkgs,
            )
            if settled:
                settled_at_ms = int((time.monotonic() - launch_t) * 1000)
            abort = abort or watch.abort_reason()
            detail = "settled" if settled else (
                f"app ANR: {anr_pkgs[0]} not responding" if anr_pkgs else
                f"{abort}, compared the final frame" if abort else
                "app process never appeared after am start" if not watch.seen else
                f"no settled frame within {cfg['settle_s']:g}s; compared the final frame"
            )
            if not frame:
                detail += "; no framebuffer capture at all"
                status = "FAIL"
            elif not watch.seen:
                # The frame is a crash dialog or the launcher, not the app —
                # keep it as a forensic artifact but do not compare it.
                actual.write_bytes(frame)
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
                    detail += "; diverges from golden"
                elif parity_budget is not None and golden_mode != "record":
                    if not verify_parity(
                        example, actual, serial, cfg, parity_budget, artifacts_dir
                    ):
                        status = "FAIL"
                        detail += "; diverges from Compose twin"
            if abort and status != "FAIL":
                status = "FAIL"
        else:  # smoke
            anr_pkgs = []
            frame, content_at = wait_for_content(
                serial, cfg["settle_s"], cfg["poll_s"], watch=watch,
                package=package, anr=anr_pkgs,
            )
            abort = abort or watch.abort_reason()
            if frame is None:
                status = "FAIL"
                detail = (
                    f"app ANR: {anr_pkgs[0]} not responding" if anr_pkgs else
                    abort or (
                        "app process never appeared after am start"
                        if not watch.seen else
                        f"screen stayed blank for {cfg['settle_s']:g}s after startup"
                    )
                )
                last = capture_screen(serial)
                if last:
                    actual.write_bytes(last)
            else:
                actual.write_bytes(frame)
                detail = "non-blank content on screen"
        # Sample before force_stop below — afterwards pidof is always empty and
        # the watcher would report a death we caused ourselves.
        died = watch.abort_reason()
    finally:
        force_stop(serial, package)

    # Memory footprint rides every example, pass or fail — as raw dump for
    # forensics and as parsed numbers for the nightly trend.
    metrics.update(
        dump_meminfo(
            serial, example_path, artifacts_dir / f"{example}.meminfo.txt"
        )
    )
    displayed = displayed_time_ms(serial, example_path)
    if displayed is not None:
        metrics["displayed_ms"] = displayed
    if content_at is not None:
        metrics["launch_to_first_frame_ms"] = int((content_at - launch_t) * 1000)
    if settled_at_ms is not None:
        metrics["settle_ms"] = settled_at_ms
    write_metrics(artifacts_dir, example, cfg, status, metrics)

    if status == "FAIL":
        dump_logcat(serial, artifacts_dir / f"{example}.logcat.txt")
        if died:
            detail += f"; {died} during capture — see logcat"

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
    for tool in ("water", "adb", "keytool"):
        if subprocess.run(["which", tool], capture_output=True).returncode != 0:
            sys.exit(f"{tool} not found in PATH.")

    serial = os.environ.get("ANDROID_SERIAL") or detect_serial()
    abi = device_abi(serial)
    arch = CLI_ARCH_FOR_ABI[abi]
    apksigner = find_apksigner()
    keystore = artifacts_dir / "e2e.keystore"
    ensure_keystore(keystore)

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
                    arch,
                    apksigner,
                    keystore,
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

    # Merge the per-example metric files this shard produced into one
    # manifest — the nightly aggregation job collects these across shards.
    shard_metrics = []
    for name in assigned:
        metrics_file = artifacts_dir / f"{name}.metrics.json"
        if not metrics_file.is_file():
            continue
        try:
            shard_metrics.append(json.loads(metrics_file.read_text()))
        except (OSError, json.JSONDecodeError):
            continue
    if shard_metrics:
        (artifacts_dir / f"metrics-shard-{args.shard_index}.json").write_text(
            json.dumps(shard_metrics, indent=2) + "\n"
        )
        print(f"---- shard {args.shard_index} metrics ----", flush=True)
        for entry in shard_metrics:
            pss = entry.get("total_pss_kb")
            pss_mb = f"{pss / 1024:.1f} MB" if pss else "-"
            apk = entry.get("apk_bytes")
            apk_mb = f"{apk / 1024 / 1024:.1f} MB" if apk else "-"
            displayed = entry.get("displayed_ms")
            first = entry.get("launch_to_first_frame_ms")
            print(
                f"{entry['example']:<{width}}  APK {apk_mb:<9}  PSS {pss_mb:<9}  "
                f"displayed {displayed or '-'} ms  "
                f"first frame {first or '-'} ms",
                flush=True,
            )

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
