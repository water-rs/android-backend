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

# `water run` prints this once the app is up; it is the only startup signal
# the CLI emits that is reliably post-launch rather than post-build.
STARTUP_SIGNAL = "Application started"
STARTUP_TIMEOUT_S = 480

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

    image = Image.open(BytesIO(png)).convert("L").resize(DOWNSCALE)
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


def capture_screen(serial: str) -> bytes:
    # exec-out keeps the PNG binary-safe; `adb shell screencap` runs through a
    # pty that can corrupt bytes on some devices.
    return adb_out(serial, "exec-out", "screencap", "-p")


def enter_demo_mode(serial: str) -> None:
    for args in DEMO_MODE_COMMANDS:
        adb(serial, *args)


# ---------- readiness waits ----------


def wait_for_start(proc: subprocess.Popen, log_file: Path) -> bool:
    deadline = time.monotonic() + STARTUP_TIMEOUT_S
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            print("run process exited before the startup signal.", file=sys.stderr)
            return False
        if log_file.exists() and STARTUP_SIGNAL in log_file.read_text(errors="replace"):
            return True
        time.sleep(1)
    print(f"Timed out waiting for the startup signal after {STARTUP_TIMEOUT_S}s.",
          file=sys.stderr)
    return False


def wait_for_settle(serial: str, timeout_s: float, poll_s: float) -> tuple[bool, bytes | None]:
    """Poll the framebuffer until two consecutive captures are byte-identical —
    the real "the app finished drawing" signal — or the timeout expires.
    Returns (settled, last frame); the last frame is returned either way so the
    caller can compare or inspect it."""
    deadline = time.monotonic() + timeout_s
    prev: bytes | None = None
    cur: bytes | None = None
    while time.monotonic() < deadline:
        cur = capture_screen(serial)
        if cur and cur == prev:
            return True, cur
        prev = cur
        time.sleep(poll_s)
    return False, cur


def wait_for_content(serial: str, timeout_s: float, poll_s: float) -> bytes | None:
    """A smoke-mode example animates forever by definition, so "non-blank
    content on screen" is the assertion. Poll captures until content appears;
    a frame that stays flat past the deadline is the failure this exists to
    catch."""
    deadline = time.monotonic() + timeout_s
    frame = b""
    while time.monotonic() < deadline:
        frame = capture_screen(serial)
        if frame and is_nonblank(frame):
            return frame
        time.sleep(poll_s)
    return None


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
) -> bool:
    if cfg["mode"] == "skip":
        print(f"Skipping {example}: {cfg['reason'] or 'no reason given'}")
        results.append((example, "SKIP", cfg["reason"]))
        return True

    print(f"::group::android-e2e:{example} (mode={cfg['mode']})", flush=True)
    with open(log_file, "wb") as log:
        proc = subprocess.Popen(
            ["water", "run", "--platform", "android", "--device", serial,
             "--path", str(example_path)],
            cwd=repo_root,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            return _run_started_example(
                example, cfg, proc, log_file, serial,
                golden_mode, goldens_dir, artifacts_dir, candidates_dir, results,
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
) -> bool:
    if not wait_for_start(proc, log_file):
        print(f"::error::Example {example} failed to start.")
        print("\n".join(log_file.read_text(errors="replace").splitlines()[-200:]))
        results.append((example, "FAIL", "failed to start"))
        return False

    actual = artifacts_dir / f"{example}.actual.png"
    status = "PASS"

    if cfg["mode"] == "verify":
        settled, frame = wait_for_settle(serial, cfg["settle_s"], cfg["poll_s"])
        detail = "settled" if settled else (
            f"no settled frame within {cfg['settle_s']:g}s; compared the final frame"
        )
        if frame is None:
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
    else:  # smoke
        frame = wait_for_content(serial, cfg["settle_s"], cfg["poll_s"])
        if frame is None:
            status = "FAIL"
            detail = f"screen stayed blank for {cfg['settle_s']:g}s after startup"
        else:
            actual.write_bytes(frame)
            detail = "non-blank content on screen"

    if golden_mode == "record" and cfg["mode"] == "verify" and status != "FAIL":
        (candidates_dir / f"{example}.png").write_bytes(actual.read_bytes())

    if status == "FAIL":
        print(f"::error::Example {example}: {detail}")
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

    log_dir = Path(args.log_dir or backend_dir / ".ci-logs")
    manifest_path = Path(args.manifest or backend_dir / "e2e" / "manifest.json")
    goldens_dir = Path(args.goldens_dir or backend_dir / "e2e" / "goldens")
    artifacts_dir = Path(args.artifacts_dir or backend_dir / ".ci-artifacts")
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
    enter_demo_mode(serial)

    results: list[tuple[str, str, str]] = []
    failures = [
        example
        for example in assigned
        if not run_example(
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
        )
    ]

    print(f"---- shard {args.shard_index}/{args.shard_total} results ----")
    width = max(len(name) for name, _, _ in results)
    for name, status, detail in results:
        print(f"{name:<{width}}  {status:<5}  {detail}")

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
