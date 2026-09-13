#!/usr/bin/env bash
# Install the Linux host libraries a waterui workspace build needs.
#
# The package list is maintained upstream in
# <waterui>/.github/actions/setup-linux-deps/action.yml — that composite action
# cannot be called from this repository (its nested `./.github/actions/*`
# reference resolves against the caller's repo root), so this script reuses the
# same list without forking it.
#
# usage: install_linux_deps.sh <path-to-waterui-checkout>
set -euo pipefail

waterui_root="${1:?usage: install_linux_deps.sh <path-to-waterui-checkout>}"
action_file="$waterui_root/.github/actions/setup-linux-deps/action.yml"
if [ ! -f "$action_file" ]; then
  echo "::error::$action_file not found"
  exit 1
fi

# Refresh only the Ubuntu archive index, the same shape the upstream
# refresh-apt-index action uses: the runner image also carries third-party
# sources (Chrome, Microsoft, gh) whose stale indexes have failed whole runs,
# and nothing we install comes from them.
if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
  list=/etc/apt/sources.list.d/ubuntu.sources
elif [ -s /etc/apt/sources.list ]; then
  list=/etc/apt/sources.list
else
  echo "::error::no Ubuntu archive source file on this runner"
  exit 1
fi
sudo apt-get update \
  -o Dir::Etc::SourceList="$list" \
  -o Dir::Etc::SourceParts=/dev/null

# The first `packages="…"` assignment in the action is the shared base list;
# later lines append per-input extras (GPU drivers, gcc versions,
# extra-packages) that a host CLI build does not need.
pkgs="$(sed -n 's/.*packages="\([^"]*\)".*/\1/p' "$action_file" | head -1)"
if [ -z "$pkgs" ]; then
  echo "::error::could not extract the package list from $action_file"
  exit 1
fi
echo "Installing Linux build dependencies: $pkgs"
# shellcheck disable=SC2086
sudo apt-get install -y --no-install-recommends $pkgs

# apt-get can exit 0 over a partially failed fetch; verify dpkg recorded every
# requested package instead of discovering the miss in a cargo log an hour on.
missing=""
for pkg in $pkgs; do
  status="$(dpkg-query --show --showformat='${Status}' "$pkg" 2>/dev/null || true)"
  if [ "$status" != "install ok installed" ]; then
    missing="$missing $pkg"
  fi
done
if [ -n "$missing" ]; then
  echo "::error::apt reported success but did not install:$missing"
  exit 1
fi
