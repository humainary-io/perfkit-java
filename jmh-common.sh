#!/usr/bin/env bash
#
# Copyright (c) 2026 William David Louth

# Shared JMH runner state. Source this file after changing to the project
# directory — the one holding this script, pom.xml, and src/. Every path
# here is project-relative and stays inside this project: nothing the suite
# measures is read, built, or installed from source, and no runner reaches into
# a sibling directory for one.
#
# That applies to the APIs exactly as it applies to the provider. This suite
# measures a provider through the APIs, so all three enter as resolved
# artifacts named by coordinates — the same footing the TCK puts them on, and
# the reason a different provider or API release is measured by changing a
# version rather than a directory layout.
#
# The coordinates come from the environment, and jmh.env is how the environment
# is configured once per checkout instead of once per command.

# Every setting a runner reads from the environment. The list is the contract:
# jmh.env may set these, `jmh.sh env` reports exactly these, and a name absent
# here is not configuration.

JMH_SETTINGS=(
  SPI_GROUP
  SPI_ARTIFACT
  SPI_VERSION
  SPI_PROVIDER
  SUBSTRATES_API_VERSION
  SERVENTIS_API_VERSION
  MAVEN_REPO_LOCAL
)

# Load jmh.env, letting the calling shell win. A file that could override an
# exported variable would make a one-off override silently ineffective, so the
# values already present are captured first and restored afterwards. This also
# makes loading idempotent: jmh.sh execs the phase runners, which source this
# file again with the first load's values already exported.

jmh_load_env () {
  local file="${JMH_ENV:-$JMH_SCRIPTS/jmh.env}"
  local name

  JMH_ENV_FILE=""
  JMH_ENV_PRESET=()

  for name in "${JMH_SETTINGS[@]}"; do
    [[ -n "${!name+set}" ]] && JMH_ENV_PRESET+=( "$name=${!name}" )
  done

  if [[ ! -f "$file" ]]; then
    # An explicitly named file that does not exist is a mistake worth reporting;
    # the absence of the default one simply means an unconfigured checkout.
    if [[ -n "${JMH_ENV:-}" ]]; then
      echo "JMH_ENV names a file that does not exist: $file" >&2
      return 1
    fi
    return 0
  fi

  JMH_ENV_FILE="$file"

  set -a
  # shellcheck disable=SC1090
  source "$file"
  set +a

  # The +"..." form is required: bash 3.2, which is what macOS ships, treats an
  # empty array expansion under `set -u` as an unbound variable, and an
  # unconfigured shell presets nothing.
  local entry
  for entry in ${JMH_ENV_PRESET[@]+"${JMH_ENV_PRESET[@]}"}; do
    export "${entry%%=*}=${entry#*=}"
  done
}

# Where a setting's value came from, for `jmh.sh env`. A configuration that
# cannot be traced is one people override twice and then distrust.

jmh_setting_origin () {
  local name="$1" entry

  for entry in ${JMH_ENV_PRESET[@]+"${JMH_ENV_PRESET[@]}"}; do
    [[ "${entry%%=*}" == "$name" ]] && { echo shell; return; }
  done

  if [[ -n "${!name:-}" ]]; then
    [[ -n "$JMH_ENV_FILE" ]] && { echo "jmh.env"; return; }
    echo shell
    return
  fi

  echo default
}

# This suite's own version, which names the jar the runners look for. It is a
# literal in the pom and is bumped there when the suite is released: it tracks
# the benchmarks, not the API or the provider they measure, and each of those
# moves on its own schedule. Nothing overrides it — a jar named for a version
# that was never built is not a useful thing to be able to ask for.

jmh_version () {
  # The first <version> in the pom is the project's own; the dependency
  # versions that follow are properties, and there is no <parent> above it.
  JMH_VERSION_VALUE="$(
    awk -F '[<>]' '/<version>/ { print $3; exit }' pom.xml
  )"

  if [[ -z "$JMH_VERSION_VALUE" ]]; then
    echo "Could not read the perfkit version from pom.xml" >&2
    return 1
  fi

  JMH_JAR="target/humainary-perfkit-${JMH_VERSION_VALUE}-jar-with-dependencies.jar"
  JMH_BUILD_METADATA="target/humainary-perfkit-${JMH_VERSION_VALUE}-build.txt"
}

# The only local build inputs are this project's own source and pom. The APIs
# and the provider alike are artifacts, so a sibling checkout of either is not
# part of the dependency graph even when one happens to be there — a runner that
# noticed it would behave differently depending on where the project sat. The
# pom is watched alongside src because dependency versions, compiler settings,
# and packaging all change the assembled benchmark jar without changing src/.

jmh_measured_sources () {
  JMH_MEASURED_SOURCES=( src pom.xml )
}

# A resolved artifact's path in the local repository.

jmh_artifact_path () {
  local group="$1" artifact="$2" version="$3"
  local repository="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"

  echo "$repository/${group//.//}/$artifact/$version/$artifact-$version.jar"
}

# The provider artifact this run measures, located in the local repository.
# Watching the artifact rather than any source keeps the staleness guarantee —
# a result belongs to the code it names — without the suite ever seeing how the
# provider is built.

jmh_provider_artifact () {
  local group artifact version

  # A provider is bundled at build time, so the jar's own metadata is the only
  # authority on which one this run measures. Reading any part of the coordinate
  # from the environment here would let a run name a provider it is not running,
  # or compose a triple that was never built at all.
  group="$( jmh_build_metadata_value build_spi_group )"
  artifact="$( jmh_build_metadata_value build_spi_artifact )"
  version="$( jmh_build_metadata_value build_spi_version )"

  JMH_PROVIDER_COORDINATES=""
  JMH_PROVIDER_ARTIFACT=""

  case "$group$artifact$version" in
    "" | *"<none>"* ) return ;;
  esac

  JMH_PROVIDER_COORDINATES="$group:$artifact:$version"
  JMH_PROVIDER_ARTIFACT="$( jmh_artifact_path "$group" "$artifact" "$version" )"
}

# The API artifacts this jar was compiled and assembled against, from the same
# metadata and for the same reason. Reinstalling an API without rebuilding the
# suite leaves a jar measuring code that no longer exists, which the staleness
# check catches here now that no API source is watched.

jmh_api_artifacts () {
  local substrates serventis

  JMH_API_ARTIFACTS=()

  substrates="$( jmh_build_metadata_value build_substrates_api_version )"
  serventis="$( jmh_build_metadata_value build_serventis_api_version )"

  [[ -n "$substrates" && "$substrates" != "<none>" ]] && JMH_API_ARTIFACTS+=(
    "$( jmh_artifact_path io.humainary.substrates humainary-substrates-api "$substrates" )"
  )

  [[ -n "$serventis" && "$serventis" != "<none>" ]] && JMH_API_ARTIFACTS+=(
    "$( jmh_artifact_path io.humainary.serventis humainary-serventis-api "$serventis" )"
  )

  return 0
}

jmh_build_metadata_value () {
  [[ -f "$JMH_BUILD_METADATA" ]] || return 0

  awk -F= -v key="$1" '$1 == key { print $2; exit }' "$JMH_BUILD_METADATA"
}

# Running a benchmark needs a provider; listing one does not. A jar built
# without provider coordinates fails deep inside ServiceLoader, which is a poor
# way to learn that the build was never told what to measure.

jmh_require_provider () {
  local built_group built_artifact built_version conflict=""

  if [[ ! -f "$JMH_BUILD_METADATA" ]]; then
    echo "Note: this jar carries no build metadata, so its provider is unknown." >&2
    return 0
  fi

  built_group="$( jmh_build_metadata_value build_spi_group )"
  built_artifact="$( jmh_build_metadata_value build_spi_artifact )"
  built_version="$( jmh_build_metadata_value build_spi_version )"

  if [[ "$built_artifact" == "<none>" ]]; then
    echo "This benchmark jar was built without a provider, so no cortex can be obtained." >&2
    echo "Rebuild naming one, for example:" >&2
    echo "  SPI_GROUP=io.example SPI_ARTIFACT=example-spi SPI_VERSION=1.0.0 ${JMH_COMMAND:-./jmh.sh} build" >&2
    return 1
  fi

  # Coordinates select a provider when the jar is assembled and have no effect
  # afterwards. Left set on a later command they describe something this run is
  # not measuring, so disagreement is an error rather than an override.
  if [[ -n "${SPI_GROUP:-}" && "$SPI_GROUP" != "$built_group" ]]; then
    conflict="${conflict}  SPI_GROUP=$SPI_GROUP, but the jar carries $built_group
"
  fi
  if [[ -n "${SPI_ARTIFACT:-}" && "$SPI_ARTIFACT" != "$built_artifact" ]]; then
    conflict="${conflict}  SPI_ARTIFACT=$SPI_ARTIFACT, but the jar carries $built_artifact
"
  fi
  if [[ -n "${SPI_VERSION:-}" && "$SPI_VERSION" != "$built_version" ]]; then
    conflict="${conflict}  SPI_VERSION=$SPI_VERSION, but the jar carries $built_version
"
  fi

  if [[ -n "$conflict" ]]; then
    echo "This jar measures $built_group:$built_artifact:$built_version." >&2
    printf "%s" "$conflict" >&2
    echo "Provider coordinates apply at build time only. Unset them, or rebuild with them." >&2
    return 1
  fi

  return 0
}

# A run that measured nothing is not a result. JMH can exit 0 after a benchmark
# throws — the iteration reports "<failure>", the summary table comes out empty,
# and an empty JSON array is written and announced like any other. Retaining
# that as a decision run is worse than losing it: the artifacts look ordinary
# and the failure is only visible to someone who reads the log.

jmh_verify_run () {
  local results="$1" log="$2" rows=""

  if [[ ! -f "$results" ]]; then
    echo "No result file was written: $results" >&2
    return 1
  fi

  if command -v jq >/dev/null 2>&1; then
    rows="$( jq 'length' < "$results" 2>/dev/null || true )"
  else
    rows="$( grep -c '"benchmark"' "$results" 2>/dev/null || true )"
  fi

  if [[ -z "$rows" || "$rows" == 0 ]]; then
    echo "The run produced no benchmark results." >&2
    echo "A pattern that matches nothing, or a benchmark that failed on its first" >&2
    echo "iteration, both end here. The log holds the reason." >&2
    return 1
  fi

  # A partial failure still leaves rows behind, so the log is checked as well.
  if grep -qE "<failure>|Benchmark had encountered error" "$log" 2>/dev/null; then
    echo "The run reported a benchmark failure, so its results are incomplete." >&2
    grep -nE "<failure>" "$log" | head -3 >&2
    return 1
  fi

  return 0
}

# The provider property is owned by SPI_PROVIDER, because that is what a run
# records. Passing it by hand through JMH arguments would set it on the fork
# without appearing in the metadata, so the two would disagree about what was
# measured.

jmh_reject_provider_override () {
  local arg

  for arg in "$@"; do
    case "$arg" in
      *io.humainary.substrates.spi.provider=* )
        echo "Do not pass io.humainary.substrates.spi.provider through JMH arguments." >&2
        echo "Use SPI_PROVIDER=<class>, which the runners forward to the fork and record." >&2
        return 1
        ;;
    esac
  done

  return 0
}

# The provider class, for when ServiceLoader would otherwise find none or
# several. This is a runtime selection rather than a build coordinate, so it is
# passed to the forked JVM on every run instead of being recorded in the jar.

jmh_provider_jvm_args () {
  JMH_PROVIDER_JVM_ARGS=()

  if [[ -n "${SPI_PROVIDER:-}" ]]; then
    JMH_PROVIDER_JVM_ARGS=( -jvmArgsAppend "-Dio.humainary.substrates.spi.provider=$SPI_PROVIDER" )
  fi
}

# Provenance helpers. A decision run's metadata is a claim about the machine it
# ran on, so these must answer on every platform the suite runs on rather than
# failing quietly into a blank field.

jmh_sha256 () {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$@"
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$@"
  else
    echo "sha256_unavailable  $*"
  fi
}

jmh_hardware () {
  local cpu="<unknown>" memory="<unknown>"

  case "$( uname -s )" in
    Darwin )
      cpu="$( sysctl -n machdep.cpu.brand_string 2>/dev/null || echo "<unknown>" )"
      memory="$( sysctl -n hw.memsize 2>/dev/null || echo "<unknown>" )"
      ;;
    Linux )
      if command -v lscpu >/dev/null 2>&1; then
        cpu="$( lscpu 2>/dev/null | awk -F: '/^Model name/ { sub(/^[ \t]+/, "", $2); print $2; exit }' )"
      fi
      if [[ -z "$cpu" || "$cpu" == "<unknown>" ]] && [[ -r /proc/cpuinfo ]]; then
        cpu="$( awk -F: '/^model name/ { sub(/^[ \t]+/, "", $2); print $2; exit }' /proc/cpuinfo )"
      fi
      if [[ -r /proc/meminfo ]]; then
        memory="$( awk '/^MemTotal:/ { print $2 * 1024; exit }' /proc/meminfo )"
      fi
      ;;
  esac

  echo "cpu=${cpu:-<unknown>}"
  echo "memory_bytes=${memory:-<unknown>}"
}

jmh_pattern () {
  case "$1" in
    all)
      printf '%s\n' '.*'
      ;;
    core)
      printf '%s\n' '.*(PipeOps\.async_emit_(admission_batch|batch|chained_batch|fanout_batch)|ConduitOps\.get_varied_(control_)?batch|QueueOps\.emit_sign_batch|ScorecardOps\.emit_vote_batch)'
      ;;
    lookup)
      printf '%s\n' '.*NameOps\.name_depth_(varied|control)_batch_(1k|10k)'
      ;;
    *)
      printf '%s\n' "$1"
      ;;
  esac
}

jmh_require_jar () {
  if [[ -f "$JMH_JAR" ]]; then
    return
  fi

  echo "Benchmark jar not found: $PWD/$JMH_JAR" >&2
  echo "Build it first with ${JMH_COMMAND:-./jmh.sh} build." >&2
  return 1
}

jmh_require_current_jar () {
  local newer_source

  jmh_require_jar || return

  jmh_measured_sources

  newer_source="$(
    find "${JMH_MEASURED_SOURCES[@]}" -type f -newer "$JMH_JAR" -print -quit
  )"

  if [[ -n "$newer_source" ]]; then
    echo "Benchmark jar is older than a local build input." >&2
    echo "Newer input: $newer_source" >&2
    echo "Rebuild with ${JMH_COMMAND:-./jmh.sh} build." >&2
    return 1
  fi

  jmh_provider_artifact

  if [[ -n "$JMH_PROVIDER_ARTIFACT" && -f "$JMH_PROVIDER_ARTIFACT" && "$JMH_PROVIDER_ARTIFACT" -nt "$JMH_JAR" ]]; then
    echo "Benchmark jar is older than the provider artifact it bundles." >&2
    echo "Newer artifact: $JMH_PROVIDER_ARTIFACT" >&2
    echo "Install the provider, then rebuild with ${JMH_COMMAND:-./jmh.sh} build." >&2
    return 1
  fi

  jmh_api_artifacts

  local api
  for api in ${JMH_API_ARTIFACTS[@]+"${JMH_API_ARTIFACTS[@]}"}; do
    if [[ -f "$api" && "$api" -nt "$JMH_JAR" ]]; then
      echo "Benchmark jar is older than an API artifact it was built against." >&2
      echo "Newer artifact: $api" >&2
      echo "Rebuild with ${JMH_COMMAND:-./jmh.sh} build." >&2
      return 1
    fi
  done
}

# A default declared in the pom, for reporting what a command will use when the
# environment says nothing.

jmh_pom_property () {
  awk -F '[<>]' -v key="$1" '$2 == key { print $3; exit }' pom.xml
}

# What the next command will actually use, and where each value came from.

jmh_report_env () {
  local name value origin

  echo "=== Perfkit configuration ==="
  if [[ -n "$JMH_ENV_FILE" ]]; then
    echo "Settings file: $JMH_ENV_FILE"
  else
    echo "Settings file: none (create one with ${JMH_COMMAND:-./jmh.sh} env --init)"
  fi
  echo

  printf '%-24s %-40s %s\n' SETTING VALUE FROM

  for name in "${JMH_SETTINGS[@]}"; do
    value="${!name:-}"
    origin="$( jmh_setting_origin "$name" )"

    if [[ -z "$value" ]]; then
      case "$name" in
        SUBSTRATES_API_VERSION ) value="$( jmh_pom_property substrates.api.version )"; origin="pom" ;;
        SERVENTIS_API_VERSION  ) value="$( jmh_pom_property serventis.api.version )"; origin="pom" ;;
        MAVEN_REPO_LOCAL       ) value="$HOME/.m2/repository"; origin="built-in" ;;
        *                      ) value="<unset>" ;;
      esac
    fi

    printf '%-24s %-40s %s\n' "$name" "$value" "$origin"
  done

  echo
  if [[ -z "${SPI_ARTIFACT:-}" ]]; then
    echo "No provider is configured, so a build would produce a suite that cannot run."
    echo "Set SPI_GROUP, SPI_ARTIFACT, and SPI_VERSION together."
  else
    local provider
    provider="$( jmh_artifact_path "${SPI_GROUP:-<unset>}" "$SPI_ARTIFACT" "${SPI_VERSION:-<unset>}" )"
    if [[ -f "$provider" ]]; then
      echo "Provider artifact: $provider"
    else
      echo "Provider artifact is not in the local repository yet:"
      echo "  $provider"
      echo "Install or download it before building; this suite never builds one."
    fi
  fi

  if [[ -f "$JMH_JAR" ]]; then
    echo "Benchmark jar:     $PWD/$JMH_JAR"
  else
    echo "Benchmark jar:     not built (${JMH_COMMAND:-./jmh.sh} build)"
  fi
}

# Write a starting jmh.env from the template, so configuring a checkout is one
# command rather than a hunt for variable names.

jmh_init_env () {
  local target="${JMH_ENV:-$JMH_SCRIPTS/jmh.env}"
  local template="$JMH_SCRIPTS/jmh.env.example"

  if [[ -e "$target" ]]; then
    echo "Already present, leaving it untouched: $target" >&2
    echo "Edit it, or remove it first to start from the template again." >&2
    return 1
  fi

  if [[ ! -f "$template" ]]; then
    echo "Template not found: $template" >&2
    return 1
  fi

  cp "$template" "$target"
  echo "Wrote $target"
  echo "Edit the provider coordinates in it, then run ${JMH_COMMAND:-./jmh.sh} build."
}

# Configuration first, then the version that names the jar every other helper
# refers to.
jmh_load_env
jmh_version
