#!/usr/bin/env python3
"""E2E helpers for the Android example suite.

Subcommands:
  config <manifest> <example>
      Print shell-eval-able configuration for one example:
      MODE, REASON, SETTLE_S, POLL_MS, TOL, MAXFRAC.
  nonblank <png>
      Exit 1 when the screenshot is effectively a single flat color.
  compare <golden> <actual> <tolerance> <max-fraction> <diff-out>
      Exit 1 when the fraction of pixels whose per-channel difference
      exceeds <tolerance> is greater than <max-fraction>. Writes an
      amplified diff image to <diff-out> on mismatch.
"""

import json
import shlex
import sys
from PIL import Image, ImageChops, ImageStat

# A settled screen of real content shows a stddev far above this; a flat
# fill — the failure mode "the app opened but rendered nothing" produces —
# sits near zero.
BLANK_STDDEV = 4.0
DOWNSCALE = (64, 64)


def cmd_config(manifest_path: str, example: str) -> int:
    with open(manifest_path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    defaults = manifest.get("defaults", {})
    entry = manifest.get("examples", {}).get(example, {})
    values = {
        "MODE": entry.get("mode", "verify"),
        "REASON": entry.get("reason", ""),
        "SETTLE_S": entry.get("settle_timeout_seconds", defaults.get("settle_timeout_seconds", 25)),
        "POLL_MS": entry.get("poll_interval_ms", defaults.get("poll_interval_ms", 400)),
        "TOL": entry.get("pixel_tolerance", defaults.get("pixel_tolerance", 8)),
        "MAXFRAC": entry.get("max_diff_fraction", defaults.get("max_diff_fraction", 0.005)),
    }
    for key, value in values.items():
        print(f"{key}={shlex.quote(str(value))}")
    return 0


def cmd_nonblank(path: str) -> int:
    image = Image.open(path).convert("L").resize(DOWNSCALE)
    stddev = ImageStat.Stat(image).stddev[0]
    print(f"nonblank: luminance stddev {stddev:.2f} (min {BLANK_STDDEV})")
    if stddev < BLANK_STDDEV:
        print("nonblank: screen is effectively a flat color", file=sys.stderr)
        return 1
    return 0


def cmd_compare(
    golden_path: str,
    actual_path: str,
    tolerance: int,
    max_fraction: float,
    diff_out: str,
) -> int:
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
        if diff_out:
            diff.point(lambda v: min(255, v * 8)).save(diff_out)
        return 1
    return 0


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    command = argv[1]
    if command == "config" and len(argv) == 4:
        return cmd_config(argv[2], argv[3])
    if command == "nonblank" and len(argv) == 3:
        return cmd_nonblank(argv[2])
    if command == "compare" and len(argv) == 7:
        return cmd_compare(argv[2], argv[3], int(argv[4]), float(argv[5]), argv[6])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
