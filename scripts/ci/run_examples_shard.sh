#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  run_examples_shard.sh --repo-root <waterui-root> --shard-index <n> --shard-total <n> [options]

Required:
  --repo-root     Absolute path to checked out waterui repository
  --shard-index   Zero-based shard index
  --shard-total   Total number of shards

Optional:
  --log-dir       Directory to store run logs (default: <repo-root>/backends/android/.ci-logs)
  --golden-mode   enforce (default): a missing or mismatching golden fails the example;
                  record: capture each example's frame into <artifacts-dir>/candidates/
                  without failing on missing or mismatching goldens
  --manifest      Example policy file (default: <repo-root>/backends/android/e2e/manifest.json)
  --goldens-dir   Golden screenshots (default: <repo-root>/backends/android/e2e/goldens)
  --artifacts-dir Screenshots, diffs and golden candidates
                  (default: <repo-root>/backends/android/.ci-artifacts)
USAGE
}

REPO_ROOT=""
SHARD_INDEX=""
SHARD_TOTAL=""
LOG_DIR=""
GOLDEN_MODE="enforce"
MANIFEST=""
GOLDENS_DIR=""
ARTIFACTS_DIR=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo-root)
      REPO_ROOT="$2"
      shift 2
      ;;
    --shard-index)
      SHARD_INDEX="$2"
      shift 2
      ;;
    --shard-total)
      SHARD_TOTAL="$2"
      shift 2
      ;;
    --log-dir)
      LOG_DIR="$2"
      shift 2
      ;;
    --golden-mode)
      GOLDEN_MODE="$2"
      shift 2
      ;;
    --manifest)
      MANIFEST="$2"
      shift 2
      ;;
    --goldens-dir)
      GOLDENS_DIR="$2"
      shift 2
      ;;
    --artifacts-dir)
      ARTIFACTS_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$REPO_ROOT" || -z "$SHARD_INDEX" || -z "$SHARD_TOTAL" ]]; then
  echo "Missing required arguments." >&2
  usage
  exit 1
fi

if ! [[ "$SHARD_INDEX" =~ ^[0-9]+$ && "$SHARD_TOTAL" =~ ^[0-9]+$ ]]; then
  echo "Shard index and total must be non-negative integers." >&2
  exit 1
fi

if (( SHARD_TOTAL <= 0 )); then
  echo "Shard total must be > 0." >&2
  exit 1
fi

if (( SHARD_INDEX < 0 || SHARD_INDEX >= SHARD_TOTAL )); then
  echo "Shard index must be within [0, shard-total)." >&2
  exit 1
fi

if [[ "$GOLDEN_MODE" != "enforce" && "$GOLDEN_MODE" != "record" ]]; then
  echo "--golden-mode must be 'enforce' or 'record'." >&2
  exit 1
fi

# android-emulator-runner executes each `script:` line through a fresh
# `sh -c`, so an exported ANDROID_SERIAL would not reach us; detect the one
# attached emulator ourselves when the caller did not pin a device.
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  ANDROID_SERIAL="$(adb devices | awk '/^emulator-[0-9]+[ \t]+device$/ {print $1; exit}')"
  if [[ -z "$ANDROID_SERIAL" ]]; then
    echo "No Android emulator/device detected and ANDROID_SERIAL is unset." >&2
    exit 1
  fi
  export ANDROID_SERIAL
fi

BACKEND_DIR="${REPO_ROOT}/backends/android"
: "${LOG_DIR:=${BACKEND_DIR}/.ci-logs}"
: "${MANIFEST:=${BACKEND_DIR}/e2e/manifest.json}"
: "${GOLDENS_DIR:=${BACKEND_DIR}/e2e/goldens}"
: "${ARTIFACTS_DIR:=${BACKEND_DIR}/.ci-artifacts}"
E2E_PY="${BACKEND_DIR}/scripts/ci/e2e.py"
RESULTS_FILE="${LOG_DIR}/results.txt"
CANDIDATES_DIR="${ARTIFACTS_DIR}/candidates"

mkdir -p "$LOG_DIR" "$ARTIFACTS_DIR" "$CANDIDATES_DIR"
: > "$RESULTS_FILE"

if [[ ! -f "$MANIFEST" ]]; then
  echo "Example manifest not found: $MANIFEST" >&2
  exit 1
fi

for tool in water adb python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool not found in PATH." >&2
    exit 1
  fi
done

EXAMPLES_ROOT="${REPO_ROOT}/examples"
if [[ ! -d "$EXAMPLES_ROOT" ]]; then
  echo "Examples directory not found: $EXAMPLES_ROOT" >&2
  exit 1
fi

mapfile -t EXAMPLES < <(
  find "$EXAMPLES_ROOT" -mindepth 1 -maxdepth 1 -type d \
    | while read -r example_dir; do
        if [[ -f "$example_dir/src/lib.rs" ]]; then
          basename "$example_dir"
        fi
      done \
    | LC_ALL=C sort
)

if (( ${#EXAMPLES[@]} == 0 )); then
  echo "No runnable examples found under $EXAMPLES_ROOT" >&2
  exit 1
fi

declare -a ASSIGNED=()
for idx in "${!EXAMPLES[@]}"; do
  if (( idx % SHARD_TOTAL == SHARD_INDEX )); then
    ASSIGNED+=("${EXAMPLES[$idx]}")
  fi
done

if (( ${#ASSIGNED[@]} == 0 )); then
  echo "Shard ${SHARD_INDEX}/${SHARD_TOTAL} has no examples assigned."
  exit 0
fi

echo "Shard ${SHARD_INDEX}/${SHARD_TOTAL} running ${#ASSIGNED[@]} examples on ${ANDROID_SERIAL} (golden-mode=${GOLDEN_MODE})"
printf 'Assigned examples: %s\n' "${ASSIGNED[*]}"

# Freeze the status bar so screenshots are comparable run to run: demo mode
# pins the clock, fixes wifi/battery, and hides notification icons. Entered
# once per emulator; the setting persists until the device reboots.
enter_demo_mode() {
  adb -s "$ANDROID_SERIAL" shell settings put global sysui_demo_allowed 1
  adb -s "$ANDROID_SERIAL" shell am broadcast -a com.android.systemui.demo -e command enter
  adb -s "$ANDROID_SERIAL" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
  adb -s "$ANDROID_SERIAL" shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
  adb -s "$ANDROID_SERIAL" shell am broadcast -a com.android.systemui.demo -e command mobile -e show false
  adb -s "$ANDROID_SERIAL" shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
  adb -s "$ANDROID_SERIAL" shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
}

capture_screen() {
  # exec-out keeps the PNG binary-safe; `adb shell screencap` runs through a
  # pty that can corrupt bytes on some devices.
  local out="$1"
  adb -s "$ANDROID_SERIAL" exec-out screencap -p > "$out"
  [[ -s "$out" ]]
}

# Poll the framebuffer until two consecutive captures are byte-identical —
# the real "the app finished drawing" signal — or the settle timeout expires.
# Returns 0 on a settled frame (written to $2), 1 on timeout (last frame is
# still written to $2 so the caller can compare or inspect it).
wait_for_settle() {
  local out="$1"
  local timeout_s="$2"
  local poll_s="$3"
  local scratch_dir
  scratch_dir="$(mktemp -d)"
  local cur="$scratch_dir/cur.png"
  local prev="$scratch_dir/prev.png"
  local deadline=$(( SECONDS + timeout_s ))

  while (( SECONDS < deadline )); do
    if capture_screen "$cur" && [[ -f "$prev" ]] && cmp -s "$cur" "$prev"; then
      cp "$cur" "$out"
      rm -rf "$scratch_dir"
      return 0
    fi
    mv -f "$cur" "$prev" 2>/dev/null || true
    sleep "$poll_s"
  done

  capture_screen "$out" || true
  rm -rf "$scratch_dir"
  return 1
}

# A smoke-mode example animates forever by definition, so "non-blank content
# on screen" is the assertion. Poll captures until content appears; a frame
# that stays flat past the deadline is the failure this exists to catch.
wait_for_content() {
  local out="$1"
  local timeout_s="$2"
  local poll_s="$3"
  local deadline=$(( SECONDS + timeout_s ))

  while (( SECONDS < deadline )); do
    capture_screen "$out"
    if python3 "$E2E_PY" nonblank "$out" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$poll_s"
  done
  return 1
}

stop_run() {
  local pid="$1"
  kill -INT "$pid" 2>/dev/null || true
  for _ in $(seq 1 20); do
    if ! kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  kill -TERM "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

wait_for_start() {
  local pid="$1"
  local log_file="$2"
  local timeout_seconds=480

  for _ in $(seq 1 "$timeout_seconds"); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "run process exited before startup signal." >&2
      return 1
    fi
    if grep -q "Application started" "$log_file"; then
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for startup signal after ${timeout_seconds}s." >&2
  return 1
}

record_result() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3" >> "$RESULTS_FILE"
}

finish_example() {
  local pid="$1"
  stop_run "$pid"
  wait "$pid" || true
  echo "::endgroup::"
}

run_example() {
  local example="$1"
  local example_path="${EXAMPLES_ROOT}/${example}"
  local log_file="${LOG_DIR}/${example}.log"

  local MODE REASON SETTLE_S POLL_MS TOL MAXFRAC
  eval "$(python3 "$E2E_PY" config "$MANIFEST" "$example")"
  local poll_s
  poll_s="$(awk "BEGIN { printf \"%.3f\", ${POLL_MS} / 1000 }")"

  if [[ "$MODE" == "skip" ]]; then
    echo "Skipping ${example}: ${REASON:-no reason given}"
    record_result "$example" "SKIP" "${REASON:-}"
    return 0
  fi

  echo "::group::android-e2e:${example} (mode=${MODE})"
  (
    cd "$REPO_ROOT"
    water run --platform android --device "$ANDROID_SERIAL" --path "$example_path"
  ) >"$log_file" 2>&1 &
  local pid=$!

  if ! wait_for_start "$pid" "$log_file"; then
    echo "::error::Example ${example} failed to start."
    tail -n 200 "$log_file" || true
    finish_example "$pid"
    record_result "$example" "FAIL" "failed to start"
    return 1
  fi

  local actual="${ARTIFACTS_DIR}/${example}.actual.png"
  local detail=""
  local status="PASS"

  if [[ "$MODE" == "verify" ]]; then
    if wait_for_settle "$actual" "$SETTLE_S" "$poll_s"; then
      detail="settled"
    else
      detail="no settled frame within ${SETTLE_S}s; compared the final frame"
    fi
    if ! python3 "$E2E_PY" nonblank "$actual"; then
      status="FAIL"
      detail="${detail}; screen stayed blank"
    else
      verify_golden "$example" "$actual" || status="FAIL"
    fi
  else
    # smoke
    if wait_for_content "$actual" "$SETTLE_S" "$poll_s"; then
      detail="non-blank content on screen"
    else
      status="FAIL"
      detail="screen stayed blank for ${SETTLE_S}s after startup"
    fi
  fi

  if [[ "$GOLDEN_MODE" == "record" && "$MODE" == "verify" && "$status" != "FAIL" ]]; then
    cp "$actual" "${CANDIDATES_DIR}/${example}.png"
  fi

  if [[ "$status" == "FAIL" ]]; then
    echo "::error::Example ${example}: ${detail}"
  else
    echo "Example ${example}: ${detail}"
  fi
  finish_example "$pid"
  record_result "$example" "$status" "$detail"
  [[ "$status" != "FAIL" ]]
}

verify_golden() {
  local example="$1"
  local actual="$2"
  local golden="${GOLDENS_DIR}/${example}.png"
  local diff_out="${ARTIFACTS_DIR}/${example}.diff.png"

  if [[ ! -f "$golden" ]]; then
    if [[ "$GOLDEN_MODE" == "record" ]]; then
      return 0
    fi
    echo "no golden at e2e/goldens/${example}.png — run the nightly workflow with" \
      "golden_mode=record and commit the candidates" >&2
    return 1
  fi

  if [[ "$GOLDEN_MODE" == "record" ]]; then
    python3 "$E2E_PY" compare "$golden" "$actual" "$TOL" "$MAXFRAC" "$diff_out" || true
    return 0
  fi

  python3 "$E2E_PY" compare "$golden" "$actual" "$TOL" "$MAXFRAC" "$diff_out"
}

enter_demo_mode

declare -a FAILURES=()
for example in "${ASSIGNED[@]}"; do
  if ! run_example "$example"; then
    FAILURES+=("$example")
  fi
done

echo "---- shard ${SHARD_INDEX}/${SHARD_TOTAL} results ----"
column -t -s $'\t' "$RESULTS_FILE" 2>/dev/null || cat "$RESULTS_FILE"

if (( ${#FAILURES[@]} > 0 )); then
  printf 'Failed examples: %s\n' "${FAILURES[*]}" >&2
  exit 1
fi
