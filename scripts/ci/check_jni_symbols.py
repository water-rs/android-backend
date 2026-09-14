#!/usr/bin/env python3
"""Verify every Kotlin `external fun` has its `Java_*` export in the app .so.

The runtime binds JNI statically: Kotlin declares `external fun` and the
Rust side must export `Java_<package>_<class>_<method>`. A mismatch only
detonates on-device as `UnsatisfiedLinkError` (water-rs/waterui#834 cost a
full nightly to surface), so this script checks the packaged library on the
host before any emulator work:

    check_jni_symbols.py <path-to-libwaterui_app.so> [runtime-src-dir]

Exits non-zero listing every expected symbol the .so does not export.
Extra Rust exports are fine — only missing ones are a bug.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

RUNTIME_SRC = Path("runtime/src/main/java")


def jni_escape(name: str) -> str:
    """Mangle one JNI name segment: `_` -> `_1`, `$` -> `_00024`."""
    return name.replace("_", "_1").replace("$", "_00024")


def expected_symbols(runtime_dir: Path) -> dict[str, set[str] | dict[str, str]]:
    """Collect required and optional `Java_*` symbols with their origins.

    An `external fun` preceded by a `// jni-optional` comment is feature-
    dependent: the consumer catches `UnsatisfiedLinkError` at registration,
    so the .so may legitimately lack it. Everything else must be exported.
    """
    expected: dict[str, str] = {}
    optional: set[str] = set()
    decl_re = re.compile(r"\b(?:class|object)\s+(\w+)")
    fun_re = re.compile(r"\bexternal\s+fun\s+(?:<[^>]*>\s*)?(\w+)\s*\(")
    # A `fun`/`init`/`class`-level line ends a pending class header: the class
    # had no body (`class AsyncResult(...)` one-liners). `val`/`@` stay legal
    # inside constructor parameter lists, so they must not forfeit.
    header_end_re = re.compile(r"\b(?:fun|init|interface|companion)\b")

    for kt in sorted(runtime_dir.rglob("*.kt")):
        text = kt.read_text()
        pkg_match = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
        if not pkg_match or "external fun" not in text:
            continue
        pkg = "_".join(jni_escape(seg) for seg in pkg_match.group(1).split("."))

        # `open` holds types whose body brace has opened and not yet closed —
        # the JNI owner of an `external fun` is the innermost such type. A
        # `pending` decl waits for its body `{`: constructor signatures and
        # supertype lists may span several lines before it appears.
        open_types: list[str] = []  # committed (name, body closes when depth <= decl_depth)
        open_depths: list[int] = []
        pending: tuple[str, int] | None = None  # (name, decl line depth)
        optional_next = False
        depth = 0
        in_block_comment = False
        for lineno, raw in enumerate(text.splitlines(), 1):
            if "jni-optional" in raw:
                optional_next = True
            line, in_block_comment = _strip_comments(raw, in_block_comment)
            decl = decl_re.search(line)
            if decl:
                pending = (decl.group(1), depth)
            opens = line.count("{") - line.count("}")
            if "{" in line and pending and opens + depth > pending[1]:
                # The pending decl's body just opened.
                open_types.append(pending[0])
                open_depths.append(pending[1])
                pending = None
            elif pending and header_end_re.search(line) and not decl:
                pending = None
            fun = fun_re.search(line)
            if fun:
                chain = (
                    "_00024".join(jni_escape(n) for n in open_types)
                    if open_types
                    # Top-level external fun lands on the file facade class.
                    else jni_escape(kt.stem) + "Kt"
                )
                symbol = f"Java_{pkg}_{chain}_{jni_escape(fun.group(1))}"
                expected[symbol] = f"{kt.relative_to(runtime_dir)}:{lineno}"
                if optional_next:
                    optional.add(symbol)
                optional_next = False
            elif line.strip():
                optional_next = False
            depth += opens
            while open_depths and depth <= open_depths[-1]:
                open_types.pop()
                open_depths.pop()
            if pending and depth < pending[1]:
                pending = None

    return {"expected": expected, "optional": optional}


def _strip_comments(line: str, in_block: bool) -> tuple[str, bool]:
    """Remove `//` and `/* */` comments so they cannot fake declarations."""
    out = []
    i = 0
    while i < len(line):
        if in_block:
            end = line.find("*/", i)
            if end < 0:
                return "".join(out), True
            i = end + 2
            in_block = False
        elif line.startswith("//", i):
            break
        elif line.startswith("/*", i):
            in_block = True
            i += 2
        else:
            out.append(line[i])
            i += 1
    return "".join(out), in_block


def defined_symbols(so_path: Path) -> set[str]:
    out = subprocess.run(
        ["nm", "-D", "--defined-only", str(so_path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return {
        m.group(1)
        for m in (re.search(r"\s(J\w+)$", line) for line in out.splitlines())
        if m
    }


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    so_path = Path(sys.argv[1])
    runtime_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else RUNTIME_SRC
    if not so_path.is_file():
        print(f"::error::{so_path} does not exist")
        return 2

    collected = expected_symbols(runtime_dir)
    expected = collected["expected"]
    optional = collected["optional"]
    defined = defined_symbols(so_path)
    missing = sorted(set(expected) - defined - optional)
    skipped = sorted(set(expected) - defined - set(missing))

    print(
        f"jni-symbols: {len(expected)} declared ({len(optional)} optional), "
        f"{len(defined)} defined in {so_path.name}"
    )
    for sym in skipped:
        print(f"jni-symbols: optional {sym} absent (feature off) — {expected[sym]}")
    if not missing:
        print("jni-symbols: every required external fun is exported")
        return 0
    print(f"::error::{len(missing)} external fun(s) have no export in {so_path.name}:")
    for sym in missing:
        print(f"::error::  {sym}  ({expected[sym]})")
    return 1


if __name__ == "__main__":
    sys.exit(main())
