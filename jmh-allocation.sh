#!/usr/bin/env bash
# Pair quantitative JMH B/op with JFR allocation-site evidence.

set -euo pipefail
JMH_INVOCATION_PWD="${JMH_INVOCATION_PWD:-$PWD}"
cd "$(dirname "$0")"
JMH_SCRIPTS="$PWD"
source "$JMH_SCRIPTS/jmh-common.sh"

if (( $# == 0 )); then
  echo "Usage: ${JMH_COMMAND:-./jmh-allocation.sh} '<benchmark-pattern>'" >&2
  exit 2
fi

PATTERN="$(jmh_pattern "$1")"
shift

jmh_reject_provider_override "$@"
jmh_require_current_jar

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

case "${JMH_RESULTS_DIR:-}" in
  "" ) ROOT="results/${TIMESTAMP}-allocation" ;;
  /* ) ROOT="$JMH_RESULTS_DIR" ;;
  *  ) ROOT="$JMH_INVOCATION_PWD/$JMH_RESULTS_DIR" ;;
esac

QUANTITATIVE="$ROOT/quantitative"
JFR_DIR="$ROOT/jfr"
mkdir -p "$QUANTITATIVE" "$JFR_DIR"

# The nested decision run receives absolute directories, so it does not resolve
# them a second time against a different working directory.
ROOT="$(cd "$ROOT" && pwd)"
QUANTITATIVE="$(cd "$QUANTITATIVE" && pwd)"
JFR_DIR="$(cd "$JFR_DIR" && pwd)"

JFR_CONFIG="$ROOT/allocation.jfc"
jfr configure \
  --input default.jfc \
  gc=high \
  allocation-profiling=maximum \
  --output "$JFR_CONFIG" \
  >/dev/null
JFR_CONFIG="$(cd "$(dirname "$JFR_CONFIG")" && pwd)/$(basename "$JFR_CONFIG")"

JMH_RESULTS_DIR="$QUANTITATIVE" JMH_PROFILER=gc \
  "$JMH_SCRIPTS/jmh-decision.sh" "$PATTERN" "$@"

JFR_COMMAND=(
  java -server -jar "$JMH_JAR" "$PATTERN"
  -wi 3 -i 5 -f 1 -w 1s -r 1s
  -jvmArgs "${JMH_JVM_ARGS:--server -Xms1g -Xmx1g -XX:+AlwaysPreTouch -XX:+UseG1GC}"
  -prof "jfr:dir=$JFR_DIR;configName=$JFR_CONFIG"
  -rf json -rff "$ROOT/jfr-results.json"
)
JFR_COMMAND+=( "$@" )

# Last, so the fork resolves the provider this run records.
jmh_provider_jvm_args
if (( ${#JMH_PROVIDER_JVM_ARGS[@]} > 0 )); then
  JFR_COMMAND+=( "${JMH_PROVIDER_JVM_ARGS[@]}" )
fi

JFR_STATUS=0
"${JFR_COMMAND[@]}" 2>&1 | tee "$ROOT/jfr-run.log" || JFR_STATUS=$?

if (( JFR_STATUS != 0 )) || ! jmh_verify_run "$ROOT/jfr-results.json" "$ROOT/jfr-run.log"; then
  {
    echo "The JFR attribution pass did not produce a usable result."
    echo "Exit status: $JFR_STATUS"
  } > "$ROOT/FAILED"
  echo >&2
  echo "Allocation attribution failed. Artifacts are retained for diagnosis: $ROOT" >&2
  exit 1
fi

recording_count=0
while IFS= read -r recording; do
  (( recording_count += 1 ))
  report="${recording%.jfr}-allocation-by-site.txt"
  {
    jfr summary "$recording"
    echo
    jfr view allocation-by-site "$recording"
  } > "$report"
done < <(find "$JFR_DIR" -name '*.jfr' -type f | sort)

if (( recording_count == 0 )); then
  echo "JFR produced no recordings under $JFR_DIR" >&2
  exit 1
fi

echo
echo "Read gc.alloc.rate.norm from $QUANTITATIVE and the allocation sites from the"
echo "*-allocation-by-site.txt reports. A zero-allocation claim needs both a stable B/op"
echo "of zero and no application allocation site; the JFR run's timing is profiler-distorted."
echo "Allocation artifacts: $ROOT"
