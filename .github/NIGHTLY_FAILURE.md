---
title: "nightly: the Android on-device suite is red"
labels: ci
---
The nightly Android on-device suite failed: {{ env.RUN_URL }}

The per-change gate (`ci.yml`) only covers JVM-side analysis and unit tests, so a failure here is on-device behavior: an example that stopped starting or rendering under the current backend or waterui `dev`, a golden screenshot that no longer matches, or hosted-runner/toolchain drift. Read the failing shard's log and the `android-e2e-artifacts-shard-*` screenshots/diffs, fix the root cause on a topic branch, and close this issue when the next nightly is green.
