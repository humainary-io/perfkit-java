#!/usr/bin/env bash
#
# Copyright (c) 2026 William David Louth
#
# Showcase runs: tokens carried through a population of circuits in a single
# JVM, a fresh one for each run, the runs one after another.
#
# Usage: ./jmh.sh showcase [--circuits N[,N...]] [--burst N[,N...]] [--tokens N[,N...]]
#                          [--minutes N] [--repeat N] [--observe on|off]
#
# --circuits, --burst and --tokens each take a comma-separated list, and every
# combination runs --repeat times, each run starting after the last has exited.
# --circuits takes whole numbers, since the heap is sized from it, and defaults
# to a million. A token count left out takes the program's default of one per
# processor, and a burst left out gives each token a billion deliveries a round.
# --minutes goes to the program unchanged.
#
# --observe on records each run's garbage collections in a .gc log beside its
# console log and, on Linux, samples the host every second: vmstat for user,
# system and stolen time, and mpstat, when installed, for each processor. It
# is off by default, so a figure is read without the observation's own cost;
# compare a run with it against one without before trusting either.
#
# The VM is G1 with -Xms equal to -Xmx and the heap pre-touched: half a gibibyte
# plus 8 KiB a circuit, capped at three quarters of the memory the machine or
# its container allows. A circuit holds about 2 KiB; with 4 KiB the old
# generation filled with dead stack chunks and collections fed on each other.
# SHOWCASE_HEAP sets the heap, and SHOWCASE_JVM_ARGS replaces the whole VM.
#
# Results go to results/<timestamp>-showcase/: the machine in metadata.txt,
# each run's console log, and its summary line in summary.txt.
#

set -euo pipefail
export JMH_INVOCATION_PWD="${JMH_INVOCATION_PWD:-$PWD}"
cd "$(dirname "$0")"
JMH_SCRIPTS="$PWD"
source "$JMH_SCRIPTS/jmh-common.sh"

usage () {
  echo "Usage: ${JMH_COMMAND:-./jmh-showcase.sh} [--circuits N[,N...]] [--burst N[,N...]] [--tokens N[,N...]] [--minutes N] [--repeat N] [--observe on|off]" >&2
  exit 2
}

CIRCUITS=1000000
BURSTS=-
TOKENS=-
MINUTES=()
REPEAT=1
OBSERVE=off

while (( $# > 0 )); do
  (( $# >= 2 )) || usage
  case "$1" in
    --circuits ) CIRCUITS="$2" ;;
    --burst    ) BURSTS="$2" ;;
    --tokens   ) TOKENS="$2" ;;
    --minutes  ) MINUTES=( --minutes "$2" ) ;;
    --repeat   ) REPEAT="$2" ;;
    --observe  ) OBSERVE="$2" ;;
    *          ) usage ;;
  esac
  shift 2
done

[[ "$CIRCUITS" =~ ^[1-9][0-9]*(,[1-9][0-9]*)*$ && "$REPEAT" =~ ^[1-9][0-9]*$ ]] || usage
[[ "$OBSERVE" == on || "$OBSERVE" == off ]] || usage
for list in "$BURSTS" "$TOKENS"; do
  [[ "$list" =~ ^[^,[:space:]]+(,[^,[:space:]]+)*$ ]] || usage
done

jmh_require_current_jar
jmh_require_provider

# The machine as the run sees it. Only the memory limit changes what the runner
# does; the rest is recorded so a result can be read against its machine.
showcase_topology () {
  local logical="" physical="" sockets="" nodes="" smt="" memory=""

  case "$( uname -s )" in
    Darwin )
      logical="$( sysctl -n hw.logicalcpu 2>/dev/null || true )"
      physical="$( sysctl -n hw.physicalcpu 2>/dev/null || true )"
      if [[ "$( sysctl -n hw.nperflevels 2>/dev/null || echo 1 )" -gt 1 ]]; then
        echo "performance_cores=$( sysctl -n hw.perflevel0.physicalcpu 2>/dev/null || echo "<unknown>" )"
        echo "efficiency_cores=$( sysctl -n hw.perflevel1.physicalcpu 2>/dev/null || echo "<unknown>" )"
      fi
      sockets=1
      nodes=1
      memory="$( sysctl -n hw.memsize 2>/dev/null || true )"
      ;;
    Linux )
      logical="$( nproc 2>/dev/null || getconf _NPROCESSORS_ONLN )"
      if command -v lscpu >/dev/null 2>&1; then
        physical="$( lscpu -p=CORE,SOCKET 2>/dev/null | grep -v '^#' | sort -u | wc -l | tr -d ' ' )"
        sockets="$( lscpu -p=SOCKET 2>/dev/null | grep -v '^#' | sort -u | wc -l | tr -d ' ' )"
        nodes="$( lscpu 2>/dev/null | awk -F: '/^NUMA node\(s\)/ { gsub(/[ \t]/, "", $2); print $2; exit }' )"
        echo "hypervisor=$( lscpu 2>/dev/null | awk -F: '/^Hypervisor vendor/ { sub(/^[ \t]+/, "", $2); print $2; exit }' )"
      fi
      [[ -r /sys/devices/system/cpu/smt/active ]] && smt="$( cat /sys/devices/system/cpu/smt/active )"
      [[ -r /proc/meminfo ]] && memory="$( awk '/^MemTotal:/ { print $2 * 1024; exit }' /proc/meminfo )"

      # A container's limits are the machine as far as the JVM is concerned.
      [[ -r /sys/fs/cgroup/cpu.max ]] && echo "cgroup_cpu_max=$( cat /sys/fs/cgroup/cpu.max )"
      if [[ -r /sys/fs/cgroup/memory.max ]]; then
        local limit
        limit="$( cat /sys/fs/cgroup/memory.max )"
        echo "cgroup_memory_max=$limit"
        [[ "$limit" != max && -n "$memory" && "$limit" -lt "$memory" ]] && memory="$limit"
      fi

      local file
      for file in /sys/class/dmi/id/sys_vendor /sys/class/dmi/id/product_name \
                  /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor \
                  /sys/kernel/mm/transparent_hugepage/enabled; do
        [[ -r "$file" ]] && echo "$( basename "$file" )=$( cat "$file" 2>/dev/null )"
      done

      # Compute Engine names its machine type on the metadata server, which only
      # answers from inside the instance.
      if grep -qs Google /sys/class/dmi/id/product_name && command -v curl >/dev/null 2>&1; then
        echo "gce_machine_type=$( curl -s -m 2 -H 'Metadata-Flavor: Google' \
          http://metadata.google.internal/computeMetadata/v1/instance/machine-type 2>/dev/null | awk -F/ '{ print $NF }' )"
      fi
      ;;
  esac

  echo "logical_processors=${logical:-<unknown>}"
  echo "physical_cores=${physical:-<unknown>}"
  echo "sockets=${sockets:-<unknown>}"
  echo "numa_nodes=${nodes:-<unknown>}"
  echo "smt_active=${smt:-<unknown>}"
  echo "usable_memory_bytes=${memory:-<unknown>}"
}

TOPOLOGY="$( showcase_topology )"
MEMORY="$( echo "$TOPOLOGY" | awk -F= '$1 == "usable_memory_bytes" { print $2 }' )"

heap_for () {
  local circuits="$1" mib cap

  if [[ -n "${SHOWCASE_HEAP:-}" ]]; then
    echo "$SHOWCASE_HEAP"
    return
  fi

  mib=$(( 512 + ( circuits * 8192 + ( 1 << 20 ) - 1 ) / ( 1 << 20 ) ))

  if [[ "$MEMORY" =~ ^[0-9]+$ ]]; then
    cap=$(( MEMORY * 3 / 4 / ( 1 << 20 ) ))
    if (( mib > cap )); then
      echo "Note: $circuits circuits want ${mib}m; capped at ${cap}m, three quarters of usable memory." >&2
      mib=$cap
    fi
  fi

  echo "${mib}m"
}

SAMPLERS=()

start_samplers () {
  local base="$1"

  # Stopping a sampler kills it, so its output is line buffered or a block of it is lost.
  [[ "$( uname -s )" == Linux ]] && command -v stdbuf >/dev/null 2>&1 || return 0
  if command -v vmstat >/dev/null 2>&1; then
    stdbuf -oL vmstat -n -t 1 > "$base.vmstat" 2>&1 &
    SAMPLERS+=( $! )
  fi
  if command -v mpstat >/dev/null 2>&1; then
    stdbuf -oL mpstat -P ALL 1 > "$base.mpstat" 2>&1 &
    SAMPLERS+=( $! )
  fi
}

stop_samplers () {
  if (( ${#SAMPLERS[@]} > 0 )); then
    kill "${SAMPLERS[@]}" 2>/dev/null || true
    wait "${SAMPLERS[@]}" 2>/dev/null || true
    SAMPLERS=()
  fi
}

trap stop_samplers EXIT

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

case "${JMH_RESULTS_DIR:-}" in
  "" ) RESULT_DIR="results/${TIMESTAMP}-showcase" ;;
  /* ) RESULT_DIR="$JMH_RESULTS_DIR" ;;
  *  ) RESULT_DIR="$JMH_INVOCATION_PWD/$JMH_RESULTS_DIR" ;;
esac

if [[ -e "$RESULT_DIR/summary.txt" ]]; then
  echo "Result directory already holds a showcase run: $RESULT_DIR" >&2
  exit 1
fi

mkdir -p "$RESULT_DIR"
RESULT_DIR="$(cd "$RESULT_DIR" && pwd)"
METADATA="$RESULT_DIR/metadata.txt"
SUMMARY="$RESULT_DIR/summary.txt"
REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"

PROVIDER_FLAG=()
[[ -n "${SPI_PROVIDER:-}" ]] && PROVIDER_FLAG=( "-Dio.humainary.substrates.spi.provider=$SPI_PROVIDER" )

{
  echo "timestamp_utc=$TIMESTAMP"
  echo "options=--circuits $CIRCUITS --burst $BURSTS --tokens $TOKENS ${MINUTES[*]:-} --repeat $REPEAT --observe $OBSERVE"
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
  fi
  echo "spi_provider_class=${SPI_PROVIDER:-<serviceloader>}"
  echo "java_home=${JAVA_HOME:-<unset>}"
  java -version 2>&1
  uname -a
  jmh_os
  uptime
  jmh_hardware
  echo "$TOPOLOGY"
} > "$METADATA"

if [[ -n "$REPO_ROOT" ]]; then
  git -C "$REPO_ROOT" status --short > "$RESULT_DIR/git-status.txt"
  git -C "$REPO_ROOT" diff --binary HEAD > "$RESULT_DIR/source-state.patch"
fi

echo "$TOPOLOGY" | sed 's/^/  /'
echo

STATUS=0
FIRST=1

for circuits in ${CIRCUITS//,/ }; do
  for burst in ${BURSTS//,/ }; do
    for tokens in ${TOKENS//,/ }; do
      for (( run = 1; run <= REPEAT; run++ )); do

        # A pause between runs, so one run's heat and its JVM's exit are not the
        # next run's start.
        (( FIRST )) || sleep 5
        FIRST=0

        if [[ -n "${SHOWCASE_JVM_ARGS+set}" ]]; then
          read -r -a VM <<< "$SHOWCASE_JVM_ARGS"
        else
          HEAP="$( heap_for "$circuits" )"
          VM=( -server "-Xms$HEAP" "-Xmx$HEAP" -XX:+AlwaysPreTouch -XX:+UseG1GC )
        fi

        LOG="$RESULT_DIR/circuits-$circuits-burst-${burst/#-/default}-tokens-${tokens/#-/default}-run-$run.log"
        [[ "$OBSERVE" == on ]] && VM+=( "-Xlog:gc*:file=${LOG%.log}.gc:uptime,level,tags" )

        ARGS=( --circuits "$circuits" ${MINUTES[@]+"${MINUTES[@]}"} )
        [[ "$burst" != - ]] && ARGS+=( --burst "$burst" )
        [[ "$tokens" != - ]] && ARGS+=( --tokens "$tokens" )

        COMMAND=(
          java ${VM[@]+"${VM[@]}"} ${PROVIDER_FLAG[@]+"${PROVIDER_FLAG[@]}"}
          -cp "$JMH_JAR" io.humainary.perfkit.showcase.Showcase "${ARGS[@]}"
        )

        printf "command=" >> "$METADATA"
        printf "%q " "${COMMAND[@]}" >> "$METADATA"
        echo >> "$METADATA"

        echo "=== ${ARGS[*]}, run $run of $REPEAT ==="
        RUN_STATUS=0
        [[ "$OBSERVE" == on ]] && start_samplers "${LOG%.log}"
        "${COMMAND[@]}" 2>&1 | tee "$LOG" || RUN_STATUS=$?
        stop_samplers

        if (( RUN_STATUS != 0 )) || ! grep -q '^summary ' "$LOG"; then
          echo "Run failed (exit $RUN_STATUS): ${ARGS[*]}" | tee -a "$RESULT_DIR/FAILED" >&2
          STATUS=1
          continue
        fi

        grep '^summary ' "$LOG" >> "$SUMMARY"
        echo

      done
    done
  done
done

if [[ -f "$SUMMARY" ]]; then
  echo "=== Showcase ==="
  awk '
    function si(v) {
      if (v >= 1e9) return sprintf("%.2f G", v / 1e9)
      if (v >= 1e6) return sprintf("%.1f M", v / 1e6)
      if (v >= 1e3) return sprintf("%.1f k", v / 1e3)
      return sprintf("%.1f ", v)
    }
    BEGIN { printf "%10s %8s %10s %13s %11s %6s %6s %13s %8s %4s %10s\n", "circuits", "tokens", "burst", "deliveries/s", "handoffs/s", "cv", "busy", "ns cpu/deliv.", "B/visit", "gc", "B/circuit" }
    {
      delete f
      for (i = 2; i <= NF; i++) { split($i, kv, "="); f[kv[1]] = kv[2] }
      printf "%10s %8s %10s %13s %11s %5.1f%% %5.0f%% %13.1f %8s %4s %10s\n", f["circuits"], f["tokens"], f["burst"], si(f["rate"]), si(f["handoff_rate"]), f["cv"] * 100, f["cpu"] * 100, f["cpu_ns"], f["bytes_per_visit"], f["collections"], f["held_per_circuit"]
    }
  ' "$SUMMARY"
fi

echo
echo "Artifacts: $RESULT_DIR"
exit "$STATUS"
