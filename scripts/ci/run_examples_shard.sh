#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  run_examples_shard.sh --repo-root <waterui-root> --shard-index <n> --shard-total <n> [--log-dir <path>]

Required:
  --repo-root     Absolute path to checked out waterui repository
  --shard-index   Zero-based shard index
  --shard-total   Total number of shards

Optional:
  --log-dir       Directory to store run logs (default: <repo-root>/backends/android/.ci-logs)
USAGE
}

REPO_ROOT=""
SHARD_INDEX=""
SHARD_TOTAL=""
LOG_DIR=""

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

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  echo "ANDROID_SERIAL must be set to an emulator/device id." >&2
  exit 1
fi

if [[ -z "$LOG_DIR" ]]; then
  LOG_DIR="${REPO_ROOT}/backends/android/.ci-logs"
fi
mkdir -p "$LOG_DIR"

if ! command -v water >/dev/null 2>&1; then
  echo "water CLI not found in PATH." >&2
  exit 1
fi

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

echo "Shard ${SHARD_INDEX}/${SHARD_TOTAL} running ${#ASSIGNED[@]} examples on ${ANDROID_SERIAL}"
printf 'Assigned examples: %s\n' "${ASSIGNED[*]}"

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

run_example() {
  local example="$1"
  local example_path="${EXAMPLES_ROOT}/${example}"
  local log_file="${LOG_DIR}/${example}.log"

  echo "::group::android-e2e:${example}"
  (
    cd "$REPO_ROOT"
    water run --platform android --device "$ANDROID_SERIAL" --path "$example_path"
  ) >"$log_file" 2>&1 &
  local pid=$!

  if ! wait_for_start "$pid" "$log_file"; then
    echo "::error::Example ${example} failed to start."
    tail -n 200 "$log_file" || true
    stop_run "$pid"
    wait "$pid" || true
    echo "::endgroup::"
    return 1
  fi

  sleep 3
  stop_run "$pid"
  wait "$pid" || true
  echo "Example ${example} started successfully."
  echo "::endgroup::"
}

for example in "${ASSIGNED[@]}"; do
  run_example "$example"
done
