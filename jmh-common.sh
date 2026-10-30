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
  JMH_JVM_ARGS
  JMH_JAVA_HOME
  MAVEN_REPO_LOCAL
)

# The settings file. A relative JMH_ENV names a file from where the command was
# typed, not from this directory, which the runner has already changed into —
# both when the file is read and when env --init creates it.

jmh_env_path () {
  case "${JMH_ENV:-}" in
    "" ) echo "$JMH_SCRIPTS/jmh.env" ;;
    /* ) echo "$JMH_ENV" ;;
    *  ) echo "${JMH_INVOCATION_PWD:-$PWD}/$JMH_ENV" ;;
  esac
}

# Load jmh.env, letting the calling shell win. A file that could override an
# exported variable would make a one-off override silently ineffective, so the
# values already present are captured first and restored afterwards.
#
# jmh.sh execs the phase runners, which source this file again with the first
# load's values exported. Loading a second time would take every one of those
# for the shell's own and lose where each came from — a repository path written
# relative to jmh.env would be re-read relative to the invocation directory, and
# a coordinate from jmh.env would be held to the rule for one set on the command
# line. So the first load exports its record, the file it read and the names the
# shell set, and a later load in a child process inherits it instead.

jmh_load_env () {
  local file name names entry shadowed=""
  local -a presets=()

  if [[ -n "${JMH_ENV_LOADED+set}" ]]; then
    JMH_ENV_FILE="$JMH_ENV_LOADED"
    return 0
  fi

  file="$( jmh_env_path )"
  JMH_ENV_FILE=""

  # Every name the file assigns is protected, not only the listed settings: a
  # runner knob such as JMH_FORKS set on the command line must win too.
  names=( "${JMH_SETTINGS[@]}" )
  if [[ -f "$file" ]]; then
    while IFS= read -r name; do
      names+=( "$name" )
    done < <( sed -nE 's/^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=.*/\2/p' "$file" )
  fi

  JMH_SHELL_SETTINGS=" "
  for name in "${names[@]}"; do
    if [[ -n "${!name+set}" ]]; then
      presets+=( "$name=${!name}" )
      JMH_SHELL_SETTINGS+="$name "
    fi
  done
  export JMH_SHELL_SETTINGS

  if [[ -f "$file" ]]; then
    file="$( cd "$( dirname "$file" )" && pwd )/$( basename "$file" )"
    JMH_ENV_FILE="$file"

    set -a
    # shellcheck disable=SC1090
    source "$file"
    set +a

    # The +"..." form is required: bash 3.2, which is what macOS ships, treats an
    # empty array expansion under `set -u` as an unbound variable, and an
    # unconfigured shell presets nothing.
    for entry in ${presets[@]+"${presets[@]}"}; do
      name="${entry%%=*}"
      [[ "${!name-}" == "${entry#*=}" ]] || shadowed="$shadowed $name"
      export "$name=${entry#*=}"
    done
  elif [[ -n "${JMH_ENV:-}" && -z "${JMH_ENV_CREATING:-}" ]]; then
    # An explicitly named file that does not exist is a mistake worth reporting,
    # unless this command is the one creating it; the absence of the default one
    # simply means an unconfigured checkout.
    echo "JMH_ENV names a file that does not exist: $file" >&2
    return 1
  fi

  # Said rather than done silently. JAVA_HOME and PATH are set in nearly every
  # shell, so an assignment to either in jmh.env never takes effect, and without
  # this nothing would say so.
  if [[ -n "$shadowed" ]]; then
    echo "Note: the shell's value replaces the one jmh.env sets for:$shadowed" >&2
    case "$shadowed " in
      *" JAVA_HOME "* | *" PATH "* )
        echo "To choose the JDK in jmh.env, set JMH_JAVA_HOME there instead." >&2
        ;;
    esac
  fi

  jmh_absolute_setting MAVEN_REPO_LOCAL
  jmh_absolute_setting JMH_JAVA_HOME

  # The JDK, for the build through JAVA_HOME, which the Maven wrapper reads, and
  # for the run through PATH, which finds the java that launches JMH and so every
  # fork, since a fork uses the launcher's own JDK.
  if [[ -n "${JMH_JAVA_HOME:-}" ]]; then
    if [[ ! -x "$JMH_JAVA_HOME/bin/java" ]]; then
      echo "JMH_JAVA_HOME names no JDK: $JMH_JAVA_HOME" >&2
      return 1
    fi
    export JAVA_HOME="$JMH_JAVA_HOME"
    export PATH="$JMH_JAVA_HOME/bin:$PATH"
  fi

  export JMH_ENV_LOADED="$JMH_ENV_FILE"
}

# A relative path given in the shell is relative to where it was typed; one
# written in jmh.env is relative to the file. Made absolute once, during the
# first load, so every process the run starts reads the same path.

jmh_absolute_setting () {
  local name="$1" base

  [[ -n "${!name:-}" && "${!name}" != /* ]] || return 0

  if jmh_from_shell "$name"; then
    base="${JMH_INVOCATION_PWD:-$PWD}"
  else
    base="$( dirname "$JMH_ENV_FILE" )"
  fi

  export "$name=$base/${!name}"
}

# Whether a setting came from the calling shell rather than from jmh.env, by the
# record the first load made.

jmh_from_shell () {
  [[ "${JMH_SHELL_SETTINGS:-}" == *" $1 "* ]]
}

# Where a setting's value came from, for `jmh.sh env`. A configuration that
# cannot be traced is one people override twice and then distrust.

jmh_setting_origin () {
  local name="$1"

  jmh_from_shell "$name" && { echo shell; return; }

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

# A resolved artifact's path in a local repository, the configured one unless
# another is named.

jmh_artifact_path () {
  local group="$1" artifact="$2" version="$3"
  local repository="${4:-${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}}"

  echo "$repository/${group//.//}/$artifact/$version/$artifact-$version.jar"
}

# The repository the jar's build resolved from, which is where the artifacts it
# bundles are to be found; the current setting may name another. A jar built
# before this was recorded falls back to the current setting.

jmh_build_repository () {
  local repository

  repository="$( jmh_build_metadata_value build_maven_repo_local )"

  echo "${repository:-${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}}"
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
  JMH_PROVIDER_ARTIFACT="$( jmh_artifact_path "$group" "$artifact" "$version" "$( jmh_build_repository )" )"
}

# The API artifacts this jar was compiled and assembled against, from the same
# metadata and for the same reason. Reinstalling an API without rebuilding the
# suite leaves a jar measuring code that no longer exists, which the staleness
# check catches here now that no API source is watched.

jmh_api_artifacts () {
  local substrates serventis repository

  JMH_API_ARTIFACTS=()

  substrates="$( jmh_build_metadata_value build_substrates_api_version )"
  serventis="$( jmh_build_metadata_value build_serventis_api_version )"
  repository="$( jmh_build_repository )"

  [[ -n "$substrates" && "$substrates" != "<none>" ]] && JMH_API_ARTIFACTS+=(
    "$( jmh_artifact_path io.humainary.substrates humainary-substrates-api "$substrates" "$repository" )"
  )

  [[ -n "$serventis" && "$serventis" != "<none>" ]] && JMH_API_ARTIFACTS+=(
    "$( jmh_artifact_path io.humainary.serventis humainary-serventis-api "$serventis" "$repository" )"
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
  # afterwards. Set on this command they describe something the run is not
  # measuring, so disagreement is an error rather than an override. Read from
  # jmh.env they are a standing default, which a one-off build is entitled to
  # depart from, so there the disagreement is reported and the run goes ahead.
  local name built drift=""
  for name in SPI_GROUP SPI_ARTIFACT SPI_VERSION; do

    case "$name" in
      SPI_GROUP )    built="$built_group" ;;
      SPI_ARTIFACT ) built="$built_artifact" ;;
      SPI_VERSION )  built="$built_version" ;;
    esac

    [[ -n "${!name:-}" && "${!name}" != "$built" ]] || continue

    if jmh_from_shell "$name"; then
      conflict="${conflict}  $name=${!name}, but the jar carries $built
"
    else
      drift="${drift}  jmh.env has $name=${!name}; the jar was built with $built
"
    fi

  done

  if [[ -n "$conflict" ]]; then
    echo "This jar measures $built_group:$built_artifact:$built_version." >&2
    printf "%s" "$conflict" >&2
    echo "Provider coordinates apply at build time only. Unset them, or rebuild with them." >&2
    return 1
  fi

  # The API versions are build coordinates too, and the same rule applies; an
  # unset one resolves to the pom's default, as the build itself would.
  local configured built_api pom_property
  for name in SUBSTRATES_API_VERSION SERVENTIS_API_VERSION; do

    case "$name" in
      SUBSTRATES_API_VERSION )
        built_api="$( jmh_build_metadata_value build_substrates_api_version )"
        pom_property=substrates.api.version
        ;;
      SERVENTIS_API_VERSION )
        built_api="$( jmh_build_metadata_value build_serventis_api_version )"
        pom_property=serventis.api.version
        ;;
    esac

    [[ -n "$built_api" ]] || continue

    configured="${!name:-$( jmh_pom_property "$pom_property" )}"

    [[ "$configured" != "$built_api" ]] || continue

    if jmh_from_shell "$name"; then
      echo "This jar was built against $name=$built_api, not $configured." >&2
      echo "API versions apply at build time only. Unset it, or rebuild with it." >&2
      return 1
    fi

    drift="${drift}  $name resolves to $configured; the jar was built against $built_api
"

  done

  # The jar bundles what its build resolved, from whichever repository that was.
  local built_repository
  built_repository="$( jmh_build_metadata_value build_maven_repo_local )"
  if [[ -n "$built_repository" && "$built_repository" != "${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}" ]]; then
    drift="${drift}  MAVEN_REPO_LOCAL resolves to ${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}; the jar was built from $built_repository
"
  fi

  if [[ -n "$drift" ]]; then
    echo "Note: this run measures what the jar was built with, not the current settings:" >&2
    printf "%s" "$drift" >&2
    echo "Rebuild to measure the configured settings." >&2
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
# records. Set any other way — in JMH arguments, in JMH_JVM_ARGS, or in the
# JAVA_TOOL_OPTIONS or JDK_JAVA_OPTIONS a fork inherits from the environment —
# it would reach the fork without appearing in the metadata, so the two would
# disagree about what was measured.

jmh_reject_provider_override () {
  local arg name

  for arg in "$@"; do
    case "$arg" in
      *io.humainary.substrates.spi.provider=* )
        echo "Do not pass io.humainary.substrates.spi.provider through JMH arguments." >&2
        echo "Use SPI_PROVIDER=<class>, which the runners forward to the fork and record." >&2
        return 1
        ;;
    esac
  done

  for name in JMH_JVM_ARGS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS; do
    case "${!name:-}" in
      *io.humainary.substrates.spi.provider=* )
        echo "Do not set io.humainary.substrates.spi.provider in $name." >&2
        echo "Use SPI_PROVIDER=<class>, which the runners forward to the fork and record." >&2
        return 1
        ;;
    esac
  done

  return 0
}

# The VM every runner measures under.
#
# JMH forks a fresh JVM per benchmark, and a forked JVM inherits nothing from
# the launcher: only -jvmArgs reaches it. So this is the one place a runner can
# say what it is measuring under, and every runner says it rather than taking
# what the host happened to choose.
#
# That matters because the host's choice is not a constant. Heap sizing scales
# with the machine's memory, and collector selection is a threshold: on a host
# with one active processor, ergonomics picks the serial collector rather than
# G1. An unconfigured run on a constrained CI container therefore measures a
# different collector from the same command on a workstation, which for an
# allocation-heavy row is a different experiment rather than a slower one.
#
# Fixing the heap at a pre-touched gigabyte also keeps a row from paying for
# heap growth it did not cause: the suite's hottest rows allocate at gigabytes
# a second, and a heap that starts small and resizes underneath them charges
# that to whatever was running at the time.
#
# JMH_JVM_ARGS replaces this wholesale. Setting it empty measures under the
# host's own ergonomics, which is a legitimate thing to want to see — just not
# the thing two runs on two machines can be compared across.

jmh_jvm_args () {
  printf '%s' "${JMH_JVM_ARGS--server -Xms1g -Xmx1g -XX:+AlwaysPreTouch -XX:+UseG1GC}"
}

# JMH's options, for reading a caller's arguments the way JMH does. Its parser
# takes an option by its full name, by any unambiguous prefix of it (-wb for
# -wbs), with its value after "=" (-wi=5), and, for a one-letter option, with
# its value attached (-f1). Matching names literally missed every one of those
# forms, so a runner added its own setting beside the caller's and JMH refused
# the duplicate. The second list holds the options that take no value.

JMH_OPTIONS=" bm bs e f foe gc h i jvm jvmArgs jvmArgsAppend jvmArgsPrepend l lp lprof lrf o opi p prof r rf rff si t tg to tu v w wbs wf wi wm wmb "
JMH_SWITCHES=" h l lp lprof lrf "

# Read a caller's JMH arguments as JMH does. JMH_SCAN_OPTIONS lists the options
# given, in order and by full name; JMH_SCAN_VALUES holds, for each, the index of
# the argument carrying its value, or -1 for a switch. An argument consumed as a
# value is not read as an option, so -jvmArgsAppend -ea sets no -e.

jmh_scan () {
  local -a args=( "$@" )
  local i=0 token key name match count value

  JMH_SCAN_OPTIONS=()
  JMH_SCAN_VALUES=()

  while (( i < ${#args[@]} )); do
    token="${args[i]}"
    i=$(( i + 1 ))

    case "$token" in
      --?* ) key="${token#--}" ;;
      -?*  ) key="${token#-}" ;;
      *    ) continue ;;
    esac

    # A full name wins over the longer names it begins: -jvmArgs is not an
    # abbreviation of -jvmArgsAppend.
    name=""
    if [[ "$JMH_OPTIONS" == *" ${key%%=*} "* ]]; then
      name="${key%%=*}"
    else
      count=0
      for match in $JMH_OPTIONS; do
        if [[ "$match" == "${key%%=*}"* ]]; then
          name="$match"
          count=$(( count + 1 ))
        fi
      done
      (( count == 1 )) || name=""
    fi

    if [[ -n "$name" ]]; then
      if [[ "$key" == *=* ]]; then
        value=$(( i - 1 ))
      elif [[ "$JMH_SWITCHES" == *" $name "* ]]; then
        value=-1
      else
        value=$i
        i=$(( i + 1 ))
      fi
    elif [[ "$token" != --* && "$JMH_OPTIONS" == *" ${key:0:1} "* && "$JMH_SWITCHES" != *" ${key:0:1} "* ]]; then
      name="${key:0:1}"
      value=$(( i - 1 ))
    else
      continue
    fi

    JMH_SCAN_OPTIONS+=( "$name" )
    JMH_SCAN_VALUES+=( "$value" )
  done
}

# Whether the caller's own JMH arguments set the named option, in any form JMH
# accepts. A runner states its own setting only when the caller has not: JMH
# refuses a single-valued option given twice, and accumulates a repeated
# -jvmArgs rather than letting the last one win, which would hand the fork both
# lists and leave the outcome to whichever flag HotSpot resolves last. Standing
# aside makes the caller's option mean what it says.

jmh_caller_sets () {
  local option="${1#-}" n
  shift

  jmh_scan "$@"

  for (( n = 0; n < ${#JMH_SCAN_OPTIONS[@]}; n++ )); do
    [[ "${JMH_SCAN_OPTIONS[n]}" == "$option" ]] && return 0
  done

  return 1
}

# The caller's JMH arguments with the provider class added, as JMH_RUN_ARGS. The
# class is for when ServiceLoader would otherwise find none or several. It is a
# runtime selection rather than a build coordinate, so it is passed to the
# forked JVM on every run instead of being recorded in the jar.
#
# It cannot simply be given as a -jvmArgsAppend of its own. JMH splits that
# option's value into separate VM arguments only when the option is given once;
# given more than once, it passes each value whole, so a caller's
# -jvmArgsAppend "-Da=1 -Db=2" would reach the fork as a single argument setting
# a to "1 -Db=2". The class therefore joins the caller's -jvmArgsAppend when
# there is exactly one. With none, its own is the only one; with several, JMH
# already passes each whole, and one more changes nothing. Either way it follows
# every VM option the caller gives.

jmh_provider_args () {
  local n at=-1 count=0 flag

  JMH_RUN_ARGS=( "$@" )

  [[ -n "${SPI_PROVIDER:-}" ]] || return 0

  flag="-Dio.humainary.substrates.spi.provider=$SPI_PROVIDER"

  jmh_scan "$@"

  for (( n = 0; n < ${#JMH_SCAN_OPTIONS[@]}; n++ )); do
    if [[ "${JMH_SCAN_OPTIONS[n]}" == jvmArgsAppend ]]; then
      count=$(( count + 1 ))
      at="${JMH_SCAN_VALUES[n]}"
    fi
  done

  if (( count == 1 && at < $# )); then
    JMH_RUN_ARGS[$at]="${JMH_RUN_ARGS[$at]} $flag"
  else
    JMH_RUN_ARGS+=( -jvmArgsAppend "$flag" )
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

# The operating system release and build. A kernel version can stay the same
# across an update that changes the system underneath it, so this is recorded
# beside `uname -a` rather than read from it.
jmh_os () {
  local release="<unknown>" build="<unknown>"

  case "$( uname -s )" in
    Darwin )
      release="$( sw_vers -productVersion 2>/dev/null || echo "<unknown>" )"
      build="$( sw_vers -buildVersion 2>/dev/null || echo "<unknown>" )"
      ;;
    Linux )
      if [[ -r /etc/os-release ]]; then
        release="$( . /etc/os-release && echo "${PRETTY_NAME:-<unknown>}" )"
      fi
      build="$( uname -r )"
      ;;
  esac

  echo "os_release=${release:-<unknown>}"
  echo "os_build=${build:-<unknown>}"
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

  # An artifact that is missing can show the jar neither stale nor current, so
  # its absence is reported rather than passed over.
  local missing=""

  jmh_provider_artifact

  if [[ -n "$JMH_PROVIDER_ARTIFACT" ]]; then
    if [[ ! -f "$JMH_PROVIDER_ARTIFACT" ]]; then
      missing="${missing}  $JMH_PROVIDER_ARTIFACT
"
    elif [[ "$JMH_PROVIDER_ARTIFACT" -nt "$JMH_JAR" ]]; then
      echo "Benchmark jar is older than the provider artifact it bundles." >&2
      echo "Newer artifact: $JMH_PROVIDER_ARTIFACT" >&2
      echo "Install the provider, then rebuild with ${JMH_COMMAND:-./jmh.sh} build." >&2
      return 1
    fi
  fi

  jmh_api_artifacts

  local api
  for api in ${JMH_API_ARTIFACTS[@]+"${JMH_API_ARTIFACTS[@]}"}; do
    if [[ ! -f "$api" ]]; then
      missing="${missing}  $api
"
    elif [[ "$api" -nt "$JMH_JAR" ]]; then
      echo "Benchmark jar is older than an API artifact it was built against." >&2
      echo "Newer artifact: $api" >&2
      echo "Rebuild with ${JMH_COMMAND:-./jmh.sh} build." >&2
      return 1
    fi
  done

  if [[ -n "$missing" ]]; then
    echo "Note: whether this jar is current cannot be checked; an artifact its build" >&2
    echo "resolved is no longer in the repository it was resolved from:" >&2
    printf "%s" "$missing" >&2
  fi
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

    # Set-but-empty is a choice for this one rather than an absence: it asks for
    # the fork to be left to the host's own ergonomics, so it must not be
    # reported as the built-in default it is overriding.
    if [[ "$name" == JMH_JVM_ARGS && -z "$value" && ${JMH_JVM_ARGS+set} == set ]]; then
      value="<host ergonomics>"
      # jmh_setting_origin reads emptiness as absence, which is right for every
      # other setting and wrong for this one, so the origin is named here.
      if jmh_from_shell JMH_JVM_ARGS; then
        origin=shell
      else
        origin=jmh.env
      fi
    elif [[ -z "$value" ]]; then
      case "$name" in
        SUBSTRATES_API_VERSION ) value="$( jmh_pom_property substrates.api.version )"; origin="pom" ;;
        SERVENTIS_API_VERSION  ) value="$( jmh_pom_property serventis.api.version )"; origin="pom" ;;
        JMH_JVM_ARGS           ) value="$( jmh_jvm_args )"; origin="built-in" ;;
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
  local target
  local template="$JMH_SCRIPTS/jmh.env.example"

  target="$( jmh_env_path )"

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
