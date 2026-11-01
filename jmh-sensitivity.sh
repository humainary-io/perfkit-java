#!/usr/bin/env bash
# Compare representative 1k/10k batches and short/long measurement durations.

set -euo pipefail
export JMH_INVOCATION_PWD="${JMH_INVOCATION_PWD:-$PWD}"
cd "$(dirname "$0")"
JMH_SCRIPTS="$PWD"
# Loaded here rather than left to the decision runs, so that jmh.env's settings
# for this runner apply and relative paths keep the meaning they had where the
# command was typed.
source "$JMH_SCRIPTS/jmh-common.sh"

if (( $# > 1 )); then
  echo "Usage: ${JMH_COMMAND:-./jmh-sensitivity.sh} [benchmark-pattern|lookup]" >&2
  exit 2
fi

PATTERN="${1:-lookup}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

case "${JMH_RESULTS_DIR:-}" in
  "" ) ROOT="results/${TIMESTAMP}-sensitivity" ;;
  /* ) ROOT="$JMH_RESULTS_DIR" ;;
  *  ) ROOT="$JMH_INVOCATION_PWD/$JMH_RESULTS_DIR" ;;
esac

mkdir -p "$ROOT"
ROOT="$(cd "$ROOT" && pwd)"

# The second decision run would otherwise record the first one's artifacts as
# source state.
export JMH_RUN_ROOT="$ROOT"
SHORT_DURATION="${JMH_SENSITIVITY_SHORT:-500ms}"
LONG_DURATION="${JMH_SENSITIVITY_LONG:-2s}"

if [[ "$SHORT_DURATION" == "$LONG_DURATION" ]]; then
  echo "Sensitivity durations must differ." >&2
  exit 2
fi

for duration in "$SHORT_DURATION" "$LONG_DURATION"; do
  JMH_RESULTS_DIR="$ROOT/$duration" \
  JMH_WARMUP_TIME="$duration" \
  JMH_MEASUREMENT_TIME="$duration" \
    "$JMH_SCRIPTS/jmh-decision.sh" "$PATTERN"
done

for duration in "$SHORT_DURATION" "$LONG_DURATION"; do
  echo
  echo "=== measurement duration $duration ==="
  awk '/^Benchmark[[:space:]]/ { table = 1 } table { print }' "$ROOT/$duration/run.log"
done

echo
echo "Compare the two tables. A per-operation score that moves materially with measurement"
echo "duration, or between the 1k and 10k forms of the same workload, means the batch is"
echo "collapsing or the harness cost is not yet amortized; it is not a usable baseline."
echo "Sensitivity artifacts: $ROOT"
