#!/usr/bin/env bash
#
# Copyright (c) 2026 William David Louth
#
# Render a JMH run as a compact throughput table, or compare two retained runs.
#
# JMH's console summary widens for @Param values. The default mode folds each
# parameter into the benchmark name as `[key=value]`, then reports the score,
# error, and implied throughput.
#
# Usage:
#   ./jmh-table.sh results.json                  # decision-run JSON (preferred)
#   ./jmh-table.sh run.log                       # JMH console output
#   ./jmh-table.sh results.out                   # a table this script rendered
#   ./jmh.sh run PipeOps | ./jmh-table.sh
#
#   ./jmh-table.sh --compare old.json new.json   # change between two runs
#
# Rows are emitted in run order. ops/sec is derived from the score, and only
# for average-time rows: a rate cannot be read off a single-shot duration or a
# secondary count metric, so those rows carry their raw unit instead.
#
# --compare calls a difference real only when it exceeds the two runs' combined
# error. Everything else prints `~`, because it is not distinguishable from
# noise no matter how large the percentage looks. A row whose error is missing
# on either side prints `?`: a run without error bars supports no verdict.

set -euo pipefail

MODE=table
SHOW_ALL=
ARGS=()

usage () {
  sed -n '5,27p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --all)       SHOW_ALL=1;     shift ;;
    --compare)   MODE=compare;   shift ;;
    -h|--help)   usage; exit 0 ;;
    --)          shift; ARGS+=( "$@" ); break ;;
    *)           ARGS+=( "$1" );  shift ;;
  esac
done

[[ ${#ARGS[@]} -eq 0 ]] && ARGS=( "-" )

##
## Normalize any accepted input to TSV: name, suffix, score, error, unit, mode.
##

read_input () {

  local input="$1" src

  if [[ "$input" == "-" ]]; then
    src="$( mktemp )"
    trap 'rm -f "$src"' RETURN
    cat > "$src"
  elif [[ -f "$input" ]]; then
    src="$input"
  else
    echo "No such file: $input" >&2
    return 1
  fi

  # JMH JSON opens with a bracket. Detect by content rather than by extension:
  # stdin arrives in a temporary file with no name to inspect, and an input's
  # format is a property of what is in it.
  local opener
  opener="$( awk 'BEGIN { FS = "" }
                  { for ( i = 1; i <= NF; i++ ) if ( $i !~ /[[:space:]]/ ) { print $i; exit } }
                 ' "$src" )"

  if [[ "$opener" == "[" || "$opener" == "{" ]]; then

    if ! command -v jq >/dev/null 2>&1; then
      echo "jq is required to read JMH JSON; pass the console log instead." >&2
      return 1
    fi

    # A single-fork run reports no error, which JMH writes as a bare NaN. Report
    # that as absent rather than letting it format as a flawless zero.
    jq -r '
      def num: if type == "number" and ( isnan | not ) then tostring else "" end;

      .[]
      | . as $r
      | ( [ ( $r.params // {} ) | to_entries[] | .key + "=" + ( .value | tostring ) ]
          | join(",") ) as $p
      | ( if $p == "" then "" else "[" + $p + "]" end ) as $suffix
      | ( $r.mode // "avgt" ) as $mode
      | [ [ $r.benchmark
          , $suffix
          , ( $r.primaryMetric.score | num )
          , ( $r.primaryMetric.scoreError | num )
          , $r.primaryMetric.scoreUnit
          , $mode
          ] ]
        + [ ( $r.secondaryMetrics // {} ) | to_entries[]
            | [ $r.benchmark + ":" + .key
              , $suffix
              , ( .value.score | num )
              , ( .value.scoreError | num )
              , .value.scoreUnit
              , $mode
              ] ]
      | .[] | @tsv
    ' "$src"

  elif head -1 "$src" | grep -q 'ns/op'; then

    # A table this script rendered. Columns are name, score (optionally carrying
    # its own unit), error, ops/sec; absent values print as "-". Parsing from the
    # right keeps the variable-width score field unambiguous.
    awk '
      /^Benchmark[ ]+/ { header = 1; next }
      !header || NF < 4 { next }
      {
        score = $2
        unit  = ( NF >= 5 ) ? $3 : "ns/op"
        error = $(NF - 1)

        if ( error == "-" ) error = ""

        # A rendered table does not carry JMH mode, but it carries the decision
        # the mode drove: a row that printed no rate was not average-time. Read
        # the mode back from the ops/sec column so single-shot rows do not
        # acquire a throughput they were deliberately denied.
        printf "%s\t\t%s\t%s\t%s\t%s\n", \
          $1, score, error, unit, ( unit == "ns/op" && $NF != "-" ? "avgt" : "ss" )
      }
    ' "$src"

  else

    # The console summary runs from the "Benchmark" header to end of input. The
    # header names the parameter columns, so a value can be labelled with its key.
    awk '
      /^Benchmark[ ]+/ {
        header = 1
        keys = 0
        for ( i = 2; i <= NF; i++ ) {
          if ( $i == "Mode" ) break
          key[++keys] = substr ( $i, 2, length ( $i ) - 2 )   # strip the parentheses
        }
        next
      }
      !header || NF == 0 { next }
      {
        params = ""
        for ( i = 1; i <= keys; i++ ) {
          if ( $(i + 1) == "N/A" ) continue
          params = ( params == "" ? "" : params "," ) key[i] "=" $(i + 1)
        }

        mode = $(keys + 2)
        unit = $NF

        # "score ± error unit", or "score unit" for a secondary metric. JMH also
        # prints a near-zero score as the two fields "≈ 0".
        if ( $(NF - 2) == "±" ) { score = $(NF - 3); error = $(NF - 1) }
        else                    { score = $(NF - 1); error = "" }
        if ( score == "≈" ) score = "≈0"

        printf "%s\t%s\t%s\t%s\t%s\t%s\n", \
          $1, ( params == "" ? "" : "[" params "]" ), score, error, unit, mode
      }
    ' "$src"

  fi
}

##
## Shared name shortening: a row is identified by class and method, and the
## package is dropped entirely.
##
## The package cannot be part of a row name because it is not stable across
## inputs. Sources here are JMH JSON, a console log, and a table this script
## rendered, and only the JSON spells a package in full. JMH abbreviates console
## names against the run inventory, so one benchmark prints as
## "CacheOps.get" alone in a run, "i.h.p.j.serventis.opt.data.CacheOps.get"
## beside other trees, and other spellings between — a package-bearing row name
## would therefore differ between two runs of the same benchmark, and compare
## would read that as one row removed and another added rather than as a delta.
##
## Keying on the first capitalized segment is what makes this inventory-proof:
## JMH abbreviates package segments only, never the class, so the class name
## survives every spelling intact. This holds while benchmark class names stay
## unique across the suite, which is also what lets "jmh.sh run PipeOps" name a
## row unambiguously. A name with no capitalized segment is returned untouched
## rather than guessed at.
##

TRIM_FN='
  function trim ( name,    parts, count, i, first, out ) {
    count = split ( name, parts, "." )
    first = 0
    for ( i = 1; i <= count; i++ )
      if ( parts[i] ~ /^[A-Z]/ ) { first = i; break }
    if ( first == 0 ) return name
    for ( i = first; i <= count; i++ )
      out = ( out == "" ? "" : out "." ) parts[i]
    return out
  }
'

case "$MODE" in

  table )

    [[ ${#ARGS[@]} -ne 1 ]] && { echo "table mode accepts one input." >&2; exit 2; }

    read_input "${ARGS[0]}" | awk -F'\t' "
      $TRIM_FN"'
      function rate ( score, unit, mode,    ns ) {
        if ( mode != "avgt" || score + 0 <= 0 ) return "-"
        if      ( unit == "ns/op" ) ns = score
        else if ( unit == "us/op" ) ns = score * 1000
        else if ( unit == "ms/op" ) ns = score * 1000000
        else if ( unit ==  "s/op" ) ns = score * 1000000000
        else return "-"
        return commas( sprintf ( "%.0f", 1000000000 / ns ) )
      }
      function commas ( n,    out ) {
        out = n
        while ( match ( out, /^-?[0-9]+[0-9]{3}/ ) )
          out = substr ( out, 1, RSTART + RLENGTH - 4 ) "," substr ( out, RSTART + RLENGTH - 3 )
        return out
      }
      {
        name = trim( $1 ) $2

        score = $3; error = $4; unit = $5; mode = $6

        n[NR] = name
        numeric = ( score ~ /^-?[0-9.]+([eE][-+]?[0-9]+)?$/ )
        # Round once, then use the rounded value for both the score and the rate
        # derived from it. A rate computed from more precision than the table
        # shows cannot be reproduced from the table, which would make re-reading
        # this output change it.
        shown = numeric ? sprintf ( "%.3f", score ) + 0 : score
        s[NR] = ( unit == "ns/op" && numeric ) ? sprintf ( "%.3f", shown ) \
              : numeric                       ? sprintf ( "%.3f %s", shown, unit ) \
              :                                 sprintf ( "%s %s", shown, unit )
        e[NR] = ( error ~ /^-?[0-9.]+$/ ) ? sprintf ( "%.3f", error ) : "-"
        o[NR] = rate( shown, unit, mode )

        if ( length ( name )  > wn ) wn = length ( name )
        if ( length ( s[NR] ) > ws ) ws = length ( s[NR] )
        if ( length ( e[NR] ) > we ) we = length ( e[NR] )
        if ( length ( o[NR] ) > wo ) wo = length ( o[NR] )
      }
      END {
        if ( NR == 0 ) { print "No benchmark rows found." > "/dev/stderr"; exit 1 }
        if ( ws < 5 ) ws = 5
        if ( wo < 7 ) wo = 7
        printf "%-*s  %*s  %*s  %*s\n", wn, "Benchmark", ws, "ns/op", we, "±", wo, "ops/sec"
        for ( i = 1; i <= NR; i++ )
          printf "%-*s  %*s  %*s  %*s\n", wn, n[i], ws, s[i], we, e[i], wo, o[i]
      }
    '
    ;;

  compare )

    [[ ${#ARGS[@]} -ne 2 ]] && { echo "--compare needs two inputs: OLD NEW" >&2; exit 2; }

    old="$( mktemp )"; new="$( mktemp )"
    trap 'rm -f "$old" "$new"' EXIT

    keyed () {
      read_input "$1" | awk -F'\t' "
        $TRIM_FN"'
        $5 == "ns/op" && $3 ~ /^[0-9.]+$/ {
          # An absent error is absent, not zero. Carrying it as 0 would give a
          # single-fork row a zero-width noise band and let any difference at all
          # read as resolved.
          printf "%s\t%s\t%s\n", trim( $1 ) $2, $3, ( $4 == "" ? "-" : $4 )
        }
      ' | sort -t"$( printf '\t' )" -k1,1
    }

    keyed "${ARGS[0]}" > "$old"
    keyed "${ARGS[1]}" > "$new"

    [[ -s "$old" ]] || { echo "No benchmark rows found in ${ARGS[0]}." >&2; exit 1; }
    [[ -s "$new" ]] || { echo "No benchmark rows found in ${ARGS[1]}." >&2; exit 1; }

    join -t"$( printf '\t' )" -a1 -a2 -e MISSING \
      -o '0,1.2,1.3,2.2,2.3' "$old" "$new" \
      | awk -F'\t' -v all="$SHOW_ALL" '
          {
            name = $1
            if ( $2 == "MISSING" ) {
              added++
              printf "999999\t%-62s %9s %9.3f %9s  ADDED\n", name, "-", $4, "-"
              next
            }
            if ( $4 == "MISSING" ) {
              removed++
              printf "999999\t%-62s %9.3f %9s %9s  REMOVED\n", name, $2, "-", "-"
              next
            }

            o = $2 + 0; n = $4 + 0
            if ( o <= 0 ) next
            d    = n - o
            pct  = d / o * 100
            ad   = ( d < 0 ) ? -d : d
            total++

            # A run that reports no error establishes no noise band, so nothing
            # can be resolved against it at any size. Say that, rather than
            # comparing against an implied zero and calling the result a change.
            if ( $3 == "-" || $5 == "-" ) {
              unjudged++
              printf "%.6f\t%-62s %9.3f %9.3f %+8.1f%%  ?\n", ad / o, name, o, n, pct
              next
            }

            oe = $3 + 0; ne = $5 + 0
            # The uncertainty of a difference between two independent runs is the
            # quadrature sum of their errors, not the linear sum: adding the bars
            # end to end overstates it and buries changes that did resolve.
            band = sqrt ( oe * oe + ne * ne )
            verdict = ( ad > band ) ? ( d > 0 ? "SLOWER" : "faster" ) : "~"
            if ( verdict != "~" ) changed++
            if ( verdict != "~" || all )
              printf "%.6f\t%-62s %9.3f %9.3f %+8.1f%%  %s\n", \
                ( verdict == "~" ) ? 0 : ad / o, name, o, n, pct, verdict
          }
          END {
            if ( ! changed )
              printf "0.000000\t%s\n", ( unjudged == total && total \
                ? "No row could be judged: one or both runs report no error bar." \
                : "No change resolved above the runs combined error." )
            printf "# %d of %d common rows moved beyond noise; %d added; %d removed", \
              changed, total, added, removed > "/dev/stderr"
            if ( unjudged )
              printf "; %d unjudged (?): no error bar on one or both sides", unjudged > "/dev/stderr"
            printf "\n" > "/dev/stderr"
          }
        ' \
      | sort -rn \
      | { printf "  %-62s %9s %9s %9s  %s\n" "benchmark" "old" "new" "delta" "verdict"; cat; } \
      | cut -f2- | sed '/^$/d' ;;

esac
