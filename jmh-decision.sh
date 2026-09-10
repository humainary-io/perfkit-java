#!/usr/bin/env bash
# Run a reproducible, multi-fork JMH measurement and retain its raw result.

set -euo pipefail
JMH_INVOCATION_PWD="${JMH_INVOCATION_PWD:-$PWD}"
cd "$(dirname "$0")"
JMH_SCRIPTS="$PWD"
source "$JMH_SCRIPTS/jmh-common.sh"

if (( $# == 0 )); then
  echo "Usage: ${JMH_COMMAND:-./jmh-decision.sh} '<benchmark-pattern>' [additional JMH options]" >&2
  exit 2
fi

PATTERN="$(jmh_pattern "$1")"
shift

jmh_reject_provider_override "$@"

jmh_require_current_jar
jmh_require_provider

# Untracked files are not necessarily source. A run's evidence is shared, and a
# stray credential, key, or scratch file in the working tree would be shared with
# it, so the default records what was present without copying what is in it.
UNTRACKED_CAPTURE="${JMH_CAPTURE_UNTRACKED:-manifest}"
case "$UNTRACKED_CAPTURE" in
  manifest | content | none ) ;;
  * )
    echo "JMH_CAPTURE_UNTRACKED must be manifest, content, or none: $UNTRACKED_CAPTURE" >&2
    exit 2
    ;;
esac

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

# The default lands in this project's results directory; an override is the
# caller's path, so it is read against the directory they typed it in.
case "${JMH_RESULTS_DIR:-}" in
  "" ) RESULT_DIR="results/${TIMESTAMP}-decision" ;;
  /* ) RESULT_DIR="$JMH_RESULTS_DIR" ;;
  *  ) RESULT_DIR="$JMH_INVOCATION_PWD/$JMH_RESULTS_DIR" ;;
esac
RESULT_JSON="$RESULT_DIR/results.json"
RUN_LOG="$RESULT_DIR/run.log"
METADATA="$RESULT_DIR/metadata.txt"
STATUS="$RESULT_DIR/git-status.txt"
SOURCE_PATCH="$RESULT_DIR/source-state.patch"
UNTRACKED_MANIFEST="$RESULT_DIR/untracked-files.txt"
if [[ -e "$RESULT_JSON" || -e "$RUN_LOG" || -e "$METADATA" ]]; then
  echo "Result directory already contains a decision run: $RESULT_DIR" >&2
  exit 1
fi
mkdir -p "$RESULT_DIR"
RESULT_DIR_PATH="$(cd "$RESULT_DIR" && pwd)"

# Provenance is recorded for the repository this project sits in, whichever that
# is, and anchored at its top level so that a run started from a subdirectory
# still captures every untracked source. A project copied without a repository
# records that fact rather than failing.
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"

FORKS="${JMH_FORKS:-3}"
WARMUP_ITERATIONS="${JMH_WARMUP_ITERATIONS:-8}"
MEASUREMENT_ITERATIONS="${JMH_MEASUREMENT_ITERATIONS:-10}"
WARMUP_TIME="${JMH_WARMUP_TIME:-1s}"
MEASUREMENT_TIME="${JMH_MEASUREMENT_TIME:-1s}"
JVM_ARGS="${JMH_JVM_ARGS:--server -Xms1g -Xmx1g -XX:+AlwaysPreTouch -XX:+UseG1GC}"

COMMAND=(
  java -server -jar "$JMH_JAR" "$PATTERN"
  -wi "$WARMUP_ITERATIONS" -i "$MEASUREMENT_ITERATIONS" -f "$FORKS"
  -w "$WARMUP_TIME" -r "$MEASUREMENT_TIME"
  -jvmArgs "$JVM_ARGS"
  -rf json -rff "$RESULT_JSON"
)
if [[ -n "${JMH_PROFILER:-}" ]]; then
  COMMAND+=( -prof "$JMH_PROFILER" )
fi
COMMAND+=( "$@" )

# Last, so that the recorded provider is the one the fork resolves. A duplicate
# -D on a JVM command line is won by the final occurrence.
jmh_provider_jvm_args
if (( ${#JMH_PROVIDER_JVM_ARGS[@]} > 0 )); then
  COMMAND+=( "${JMH_PROVIDER_JVM_ARGS[@]}" )
fi

{
  echo "timestamp_utc=$TIMESTAMP"
  if [[ -n "$REPO_ROOT" ]]; then
    echo "git_repository=$REPO_ROOT"
    echo "git_commit=$(git -C "$REPO_ROOT" rev-parse HEAD)"
    echo "git_dirty_files=$(git -C "$REPO_ROOT" status --short | wc -l | tr -d ' ')"
  else
    echo "git_repository=<none>"
  fi
  jmh_sha256 "$JMH_JAR"
  if [[ -f "$JMH_BUILD_METADATA" ]]; then
    cat "$JMH_BUILD_METADATA"
  else
    echo "benchmark_build_metadata=<unavailable>"
  fi
  echo "spi_provider_class=${SPI_PROVIDER:-<serviceloader>}"
  echo "untracked_capture=$UNTRACKED_CAPTURE"
  echo "java_home=${JAVA_HOME:-<unset>}"
  java -version 2>&1
  uname -a
  uptime
  jmh_hardware
  printf "command="
  printf "%q " "${COMMAND[@]}"
  echo
} > "$METADATA"

if [[ -n "$REPO_ROOT" ]]; then
  git -C "$REPO_ROOT" status --short > "$STATUS"
  git -C "$REPO_ROOT" diff --binary HEAD > "$SOURCE_PATCH"

  if [[ "$UNTRACKED_CAPTURE" != none ]]; then
    : > "$UNTRACKED_MANIFEST"
    while IFS= read -r -d '' untracked; do
      ( cd "$REPO_ROOT" && jmh_sha256 "$untracked" ) >> "$UNTRACKED_MANIFEST"

      if [[ "$UNTRACKED_CAPTURE" == content ]]; then
        untracked_status=0
        git -C "$REPO_ROOT" diff --binary --no-index /dev/null "$untracked" >> "$SOURCE_PATCH" || untracked_status=$?
        if (( untracked_status != 1 )); then
          echo "Could not capture untracked source: $untracked" >&2
          exit "$untracked_status"
        fi
      fi
    done < <(git -C "$REPO_ROOT" ls-files --others --exclude-standard -z)
  fi
else
  echo "No repository: source state was not captured for this run." > "$STATUS"
fi

if [[ "$UNTRACKED_CAPTURE" == content ]]; then
  echo "Untracked file contents are embedded in this run's source-state.patch." >&2
  echo "Review it before sharing the run." >&2
fi

printf "Decision run: "
printf "%q " "${COMMAND[@]}"
echo
RUN_STATUS=0
"${COMMAND[@]}" 2>&1 | tee "$RUN_LOG" || RUN_STATUS=$?

if (( RUN_STATUS != 0 )) || ! jmh_verify_run "$RESULT_JSON" "$RUN_LOG"; then
  {
    echo "This run did not produce a usable result."
    echo "Exit status: $RUN_STATUS"
  } > "$RESULT_DIR/FAILED"
  echo >&2
  echo "This is not a decision run. Artifacts are retained for diagnosis:" >&2
  echo "$RESULT_DIR_PATH" >&2
  exit 1
fi

echo
echo "Read the score with its error before acting on it: a result whose 99.9% confidence"
echo "interval exceeds about a tenth of the score is noise, not a measurement."
echo "Artifacts: $RESULT_DIR_PATH"
