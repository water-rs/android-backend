#!/usr/bin/env python3
"""Install the Linux host libraries a waterui workspace build needs.

The package list is maintained upstream in
<waterui>/.github/actions/setup-linux-deps/action.yml — that composite action
cannot be called from this repository (its nested `./.github/actions/*`
reference resolves against the caller's repo root), so this script reuses the
same list without forking it.

usage: install_linux_deps.py <path-to-waterui-checkout>
"""

import re
import subprocess
import sys
from pathlib import Path


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True)


def dpkg_installed(package: str) -> bool:
    result = subprocess.run(
        ["dpkg-query", "--show", "--showformat=${Status}", package],
        capture_output=True,
        text=True,
    )
    return result.returncode == 0 and result.stdout.strip() == "install ok installed"


def main(waterui_root: str) -> int:
    action_file = Path(waterui_root) / ".github/actions/setup-linux-deps/action.yml"
    if not action_file.is_file():
        sys.exit(f"::error::{action_file} not found")

    # Refresh only the Ubuntu archive index, the same shape the upstream
    # refresh-apt-index action uses: the runner image also carries third-party
    # sources (Chrome, Microsoft, gh) whose stale indexes have failed whole
    # runs, and nothing we install comes from them.
    deb822 = Path("/etc/apt/sources.list.d/ubuntu.sources")
    legacy = Path("/etc/apt/sources.list")
    if deb822.is_file():
        source_list = str(deb822)
    elif legacy.is_file() and legacy.stat().st_size > 0:
        source_list = str(legacy)
    else:
        sys.exit("::error::no Ubuntu archive source file on this runner")
    run([
        "sudo", "apt-get", "update",
        "-o", f"Dir::Etc::SourceList={source_list}",
        "-o", "Dir::Etc::SourceParts=/dev/null",
    ])

    # The first `packages="…"` assignment in the action is the shared base
    # list; later lines append per-input extras (GPU drivers, gcc versions,
    # extra-packages) that a host CLI build does not need.
    match = re.search(r'packages="([^"]*)"', action_file.read_text())
    if match is None:
        sys.exit(f"::error::could not extract the package list from {action_file}")
    pkgs = match.group(1).split()
    print(f"Installing Linux build dependencies: {' '.join(pkgs)}")
    run(["sudo", "apt-get", "install", "-y", "--no-install-recommends", *pkgs])

    # apt-get can exit 0 over a partially failed fetch; verify dpkg recorded
    # every requested package instead of discovering the miss in a cargo log
    # an hour on.
    missing = [pkg for pkg in pkgs if not dpkg_installed(pkg)]
    if missing:
        sys.exit(f"::error::apt reported success but did not install: {' '.join(missing)}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
