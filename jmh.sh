#!/usr/bin/env bash
#
# Copyright (c) 2025 William David Louth
#
# Performance validation entry point. The build and run phases remain separate
# so measurement never starts on a machine heated by compilation.
#
# Usage (from this directory, or by path from anywhere):
#   ./jmh.sh build                             # Build the benchmark jar
#   ./jmh.sh list [pattern|suite]              # List the current jar
#   ./jmh.sh run <pattern|suite> [JMH options] # Run without rebuilding
#   ./jmh.sh decision <pattern|suite>          # Retained multi-fork result
#   ./jmh.sh allocation <pattern|suite>        # B/op plus JFR attribution
#   ./jmh.sh sensitivity [pattern|lookup]      # Duration/batch-size matrix
#   ./jmh.sh table <results.json>              # Render a result table
#   ./jmh.sh compare <old.json> <new.json>     # Compare retained runs
#   ./jmh.sh suites                            # List named benchmark suites
#   ./jmh.sh env [--init]                      # Show settings, or write jmh.env
#
# Nothing measured is built here. The APIs and the provider are resolved from
# the local Maven repository at the versions named below, so all three must be
# installed before building the suite.
#
# Settings (environment, or jmh.env beside this script):
#   SPI_GROUP=io.example SPI_ARTIFACT=my-spi SPI_VERSION=1.0.0 ./jmh.sh build
#   SPI_PROVIDER=com.example.ExampleCortexProvider ./jmh.sh run <pattern>
#   SUBSTRATES_API_VERSION / SERVENTIS_API_VERSION override the pom defaults.
#
# Run ./jmh.sh env --init once to write jmh.env, then edit it and the commands
# above take no environment at all.
#

set -euo pipefail

usage () {
  # Read the resolved script, not "$0": by the time this runs the shell has
  # changed directory, and a relative invocation path no longer resolves.
  sed -n '5,30p' "$JMH_SELF" | sed 's/^# \{0,1\}//'
}

# This project is self-contained: the runners, the benchmark module, and the
# retained results all live under this directory, and every path below is
# relative to it. Resolve the script directory (following a symlink if this was
# invoked through one) and work from there, remembering where the command was
# typed so that a path the caller passes still means what it said in their
# shell.
JMH_COMMAND="$0"
JMH_INVOCATION_PWD="$PWD"
export JMH_COMMAND JMH_INVOCATION_PWD

cd "$(dirname "$0")"
SELF="$(basename "$0")"
if [[ -L "$SELF" ]]; then
  JMH_TARGET="$(readlink "$SELF")"
  cd "$(dirname "$JMH_TARGET")"
  SELF="$(basename "$JMH_TARGET")"
fi
JMH_SCRIPTS="$PWD"
JMH_SELF="$JMH_SCRIPTS/$SELF"

# A path argument belongs to the caller's working directory, not to this one.
jmh_resolve_paths () {
  JMH_RESOLVED=()

  local arg
  for arg in "$@"; do
    case "$arg" in
      -* | /* ) JMH_RESOLVED+=( "$arg" ) ;;
      *       ) JMH_RESOLVED+=( "$JMH_INVOCATION_PWD/$arg" ) ;;
    esac
  done
}

JMH_ARGS=()

while (( $# > 0 )); do
  case "$1" in
    --)
      shift
      JMH_ARGS+=( "$@" )
      break
      ;;
    *)
      JMH_ARGS+=( "$1" )
      shift
      ;;
  esac
done

source "$JMH_SCRIPTS/jmh-common.sh"

case "${JMH_ARGS[0]:-}" in
  build)
    if (( ${#JMH_ARGS[@]} != 1 )); then
      echo "Usage: $JMH_COMMAND build" >&2
      exit 2
    fi

    # A provider is named by three coordinates or by none. Maven resolves the
    # profile's groupId and version from properties, so a partial set produces
    # an unresolvable dependency rather than a clear error. This belongs to
    # build alone: every other command inherits its provider from the jar.
    if [[ -n "${SPI_GROUP:-}${SPI_ARTIFACT:-}${SPI_VERSION:-}" ]]; then
      if [[ -z "${SPI_GROUP:-}" || -z "${SPI_ARTIFACT:-}" || -z "${SPI_VERSION:-}" ]]; then
        echo "SPI_GROUP, SPI_ARTIFACT, and SPI_VERSION must be supplied together." >&2
        exit 2
      fi
    fi

    JMH_ARGS=()
    ACTION=build
    ;;
  list)
    ACTION=run
    if (( ${#JMH_ARGS[@]} == 1 )); then
      JMH_ARGS=( -l )
    else
      JMH_ARGS=( -l "$(jmh_pattern "${JMH_ARGS[1]}")" "${JMH_ARGS[@]:2}" )
    fi
    ;;
  run)
    if (( ${#JMH_ARGS[@]} < 2 )); then
      echo "Usage: $JMH_COMMAND run <benchmark-pattern|suite> [JMH options]" >&2
      exit 2
    fi
    ACTION=run
    JMH_ARGS=( "$(jmh_pattern "${JMH_ARGS[1]}")" "${JMH_ARGS[@]:2}" )
    ;;
  decision|allocation|sensitivity)
    command="${JMH_ARGS[0]}"
    exec "$JMH_SCRIPTS/jmh-${command}.sh" "${JMH_ARGS[@]:1}"
    ;;
  table)
    jmh_resolve_paths "${JMH_ARGS[@]:1}"
    if (( ${#JMH_RESOLVED[@]} == 0 )); then
      exec "$JMH_SCRIPTS/jmh-table.sh"
    fi
    exec "$JMH_SCRIPTS/jmh-table.sh" "${JMH_RESOLVED[@]}"
    ;;
  compare)
    jmh_resolve_paths "${JMH_ARGS[@]:1}"
    if (( ${#JMH_RESOLVED[@]} == 0 )); then
      exec "$JMH_SCRIPTS/jmh-table.sh" --compare
    fi
    exec "$JMH_SCRIPTS/jmh-table.sh" --compare "${JMH_RESOLVED[@]}"
    ;;
  env)
    case "${JMH_ARGS[1]:-}" in
      "")      jmh_report_env; exit ;;
      --init)  jmh_init_env; exit ;;
      *)
        echo "Usage: $JMH_COMMAND env [--init]" >&2
        exit 2
        ;;
    esac
    ;;
  suites)
    echo "all     complete benchmark inventory"
    echo "core    emission boundaries, topology, pooled lookup, and semantic ascent"
    echo "lookup  Name.depth target/control pairs at 1k and 10k"
    exit
    ;;
  -h|--help)
    usage
    exit
    ;;
  "")
    usage
    exit
    ;;
  *)
    echo "Unknown command: ${JMH_ARGS[0]}" >&2
    usage >&2
    exit 2
    ;;
esac

BUILD_MAVEN_ARGS=()
[[ -n "${SPI_GROUP:-}" ]] && BUILD_MAVEN_ARGS+=( "-Dsubstrates.spi.groupId=$SPI_GROUP" )
[[ -n "${SPI_ARTIFACT:-}" ]] && BUILD_MAVEN_ARGS+=( "-Dsubstrates.spi.artifactId=$SPI_ARTIFACT" )
[[ -n "${SPI_VERSION:-}" ]] && BUILD_MAVEN_ARGS+=( "-Dsubstrates.spi.version=$SPI_VERSION" )
# Maven resolution and the later artifact-staleness lookup must use the same
# local repository when the caller selects one outside ~/.m2/repository.
[[ -n "${MAVEN_REPO_LOCAL:-}" ]] && BUILD_MAVEN_ARGS+=( "-Dmaven.repo.local=$MAVEN_REPO_LOCAL" )

# The API versions are independent of this suite's own version, and the pom
# carries the default for each. Resolving them here rather than leaving them to
# Maven lets the build announce and record what it measured against.
SUBSTRATES_API_VERSION_VALUE="${SUBSTRATES_API_VERSION:-$( jmh_pom_property substrates.api.version )}"
SERVENTIS_API_VERSION_VALUE="${SERVENTIS_API_VERSION:-$( jmh_pom_property serventis.api.version )}"
BUILD_MAVEN_ARGS+=(
  "-Dsubstrates.api.version=$SUBSTRATES_API_VERSION_VALUE"
  "-Dserventis.api.version=$SERVENTIS_API_VERSION_VALUE"
)

if [[ "$ACTION" == build ]]; then
  # Nothing measured is built here. The APIs and the provider are resolved from
  # the local Maven repository at the versions the environment names, so this
  # phase compiles the benchmarks and assembles the jar and does no more.
  echo "=== APIs: substrates $SUBSTRATES_API_VERSION_VALUE, serventis $SERVENTIS_API_VERSION_VALUE ==="
  echo

  if [[ -n "${SPI_ARTIFACT:-}" ]]; then
    echo "=== Provider: $SPI_GROUP:$SPI_ARTIFACT:$SPI_VERSION ==="
  else
    echo "=== No provider supplied ==="
    echo "The suite will build, but benchmarks cannot obtain a cortex without one."
    echo "Supply SPI_GROUP, SPI_ARTIFACT, and SPI_VERSION to measure a provider."
  fi
  echo

  echo "=== Building perfkit ==="
  ./mvnw clean package "${BUILD_MAVEN_ARGS[@]}" -Dguice_custom_class_loading=CHILD

  {
    echo "build_version=$JMH_VERSION_VALUE"
    echo "build_spi_group=${SPI_GROUP:-<none>}"
    echo "build_spi_artifact=${SPI_ARTIFACT:-<none>}"
    echo "build_spi_version=${SPI_VERSION:-<none>}"
    echo "build_substrates_api_version=$SUBSTRATES_API_VERSION_VALUE"
    echo "build_serventis_api_version=$SERVENTIS_API_VERSION_VALUE"
  } > "$JMH_BUILD_METADATA"
fi

if [[ "$ACTION" == run ]]; then
  jmh_require_jar
  [[ "${JMH_ARGS[0]:-}" == "-l" ]] || jmh_require_provider
  jmh_reject_provider_override "${JMH_ARGS[@]}"

  echo
  if [[ "${JMH_ARGS[0]:-}" == "-l" ]]; then
    echo "=== Listing JMH benchmarks ==="
  else
    echo "=== Running JMH benchmarks ==="
  fi
  jmh_provider_jvm_args

  if (( ${#JMH_ARGS[@]} == 0 )); then
    java -server -jar "$JMH_JAR"
  elif (( ${#JMH_PROVIDER_JVM_ARGS[@]} == 0 )); then
    java -server -jar "$JMH_JAR" "${JMH_ARGS[@]}"
  else
    java -server -jar "$JMH_JAR" "${JMH_ARGS[@]}" "${JMH_PROVIDER_JVM_ARGS[@]}"
  fi
fi
