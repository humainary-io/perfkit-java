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
# --compare reads time per operation, in whatever unit each run reported it,
# and allocation per operation. It calls a difference real only when it exceeds
# the two runs' combined error. Everything else prints `~`, because it is not distinguishable from
# noise no matter how large the percentage looks. A row whose error is missing
# on either side prints `?`: a run without error bars supports no verdict.
#
# Where both inputs sit beside the metadata.txt a decision run records,
# --compare also checks the system each run was measured on: kernel, operating
# system release and build, CPU, and JVM. If any of them differ it warns, since
# a difference between the runs may then come from the system, not the code.

set -euo pipefail

MODE=table
SHOW_ALL=
ARGS=()

usage () {
  # The whole header from its first prose line: a fixed line range cuts it off
  # as soon as it grows.
  awk 'NR >= 5 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
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
        # the mode drove: only an average-time row printed a rate, in whichever
        # time unit it was reported. Read the mode back from the ops/sec column
        # so single-shot rows do not acquire a throughput they were deliberately
        # denied, and average-time rows keep the one they were given.
        printf "%s\t\t%s\t%s\t%s\t%s\n", \
          $1, score, error, unit, ( $NF != "-" ? "avgt" : "ss" )
      }
    ' "$src"

  else

    # The console summary runs from the "Benchmark" header to end of input. The
    # header names the parameter columns, so a value can be labelled with its key.
    awk '
      # JMH prints a number too small for its precision as "≈ 0", or as a power
      # of ten in superscript, "≈ 10⁻⁵". Read back as a number, so the row stays
      # comparable rather than dropping out as text.
      function approximate ( v,    digits, i ) {
        if ( v !~ /^10/ ) return v
        v = substr ( v, 3 )
        gsub ( /⁻/, "-", v )
        split ( "⁰ ¹ ² ³ ⁴ ⁵ ⁶ ⁷ ⁸ ⁹", digits, " " )
        for ( i = 1; i <= 10; i++ ) gsub ( digits[i], i - 1, v )
        return "1e" v
      }
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

        # "score ± error unit", or "score unit" for a row with no error, read
        # from the right. An approximate number takes two fields, so the next
        # field to the left depends on whether the last one read was one.
        j = NF - 1
        if ( $(j - 1) == "≈" ) { last = approximate( $j ); j -= 2 }
        else                   { last = $j;                j -= 1 }
        if ( $j == "±" ) {
          error = last
          j--
          score = ( $(j - 1) == "≈" ) ? approximate( $j ) : $j
        } else {
          score = last
          error = ""
        }

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

# A number as JMH, jq, or this script prints it, exponent form included: jq
# before 1.7 writes a score of 0.00001 as 1e-05, and a test for digits and
# points alone dropped that row from every comparison without a word.

NUM_FN='
  function numeric ( v ) {
    return v ~ /^-?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][-+]?[0-9]+)?$/
  }
'

case "$MODE" in

  table )

    [[ ${#ARGS[@]} -ne 1 ]] && { echo "table mode accepts one input." >&2; exit 2; }

    read_input "${ARGS[0]}" | awk -F'\t' "
      $TRIM_FN $NUM_FN"'
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
        isnum = numeric( score )
        # Round once, then use the rounded value for both the score and the rate
        # derived from it. A rate computed from more precision than the table
        # shows cannot be reproduced from the table, which would make re-reading
        # this output change it.
        shown = isnum ? sprintf ( "%.3f", score ) + 0 : score
        s[NR] = ( unit == "ns/op" && isnum ) ? sprintf ( "%.3f", shown ) \
              : isnum                       ? sprintf ( "%.3f %s", shown, unit ) \
              :                               sprintf ( "%s %s", shown, unit )
        e[NR] = numeric( error ) ? sprintf ( "%.3f", error ) : "-"
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
        # The error heading is one multi-byte character. An awk that measures
        # width in bytes pads it one column short and shifts the rest of the
        # heading off its data; widening the field by the overcount corrects
        # that, and costs nothing where length() already counts characters.
        wh = we + length ( "±" ) - 1
        printf "%-*s  %*s  %*s  %*s\n", wn, "Benchmark", ws, "ns/op", wh, "±", wo, "ops/sec"
        for ( i = 1; i <= NR; i++ )
          printf "%-*s  %*s  %*s  %*s\n", wn, n[i], ws, s[i], we, e[i], wo, o[i]
      }
    '
    ;;

  compare )

    [[ ${#ARGS[@]} -ne 2 ]] && { echo "--compare needs two inputs: OLD NEW" >&2; exit 2; }

    # What a decision run recorded about the system it ran on, one field per
    # line. Nothing for an input without that record beside it.
    system () {
      local metadata
      [[ "$1" == "-" ]] && return 0
      metadata="$( dirname "$1" )/metadata.txt"
      [[ -f "$metadata" ]] || return 0
      awk '
        # uname -a names the host second; a rename is not a different system.
        /^(Darwin|Linux) /              { $2 = ""; print "kernel\t" $0; next }
        /^os_(release|build)=/          { print substr( $0, 1, index( $0, "=" ) - 1 ) "\t" substr( $0, index( $0, "=" ) + 1 ); next }
        /^cpu=/                         { print "cpu\t" substr( $0, 5 ); next }
        /version "/ && ! seen_java++    { print "java\t" $0; next }
        /VM \(build / && ! seen_vm++    { print "vm\t" $0; next }
      ' "$metadata" | sort -t"$( printf '\t' )" -k1,1
    }

    old_system="$( system "${ARGS[0]}" )"
    new_system="$( system "${ARGS[1]}" )"

    if [[ -n "$old_system" && -n "$new_system" ]]; then
      # Only fields both runs recorded are compared: an older run that lacks one
      # says nothing about it either way.
      mismatches="$(
        join -t"$( printf '\t' )" <( echo "$old_system" ) <( echo "$new_system" ) \
          | awk -F'\t' '$2 != $3 { printf "#   %s: %s\n#   %*s  now %s\n", $1, $2, length( $1 ), "", $3 }'
      )"
      if [[ -n "$mismatches" ]]; then
        {
          echo "# warning: these runs were measured on different systems, so a difference"
          echo "# below may come from the system rather than the code."
          echo "$mismatches"
        } >&2
      fi
    fi

    old="$( mktemp )"; new="$( mktemp )"
    trap 'rm -f "$old" "$new"' EXIT

    # Time per operation, in any unit, and allocation per operation are
    # compared; rates and counts depend on how long an iteration ran, so they
    # are not. How many rows that leaves out is said rather than left to be
    # noticed, and so is a row in a compared unit with no number to compare.
    keyed () {
      read_input "$1" | awk -F'\t' -v source="$1" "
        $TRIM_FN $NUM_FN"'
        $5 !~ /^(ns|us|ms|s)\/op$/ && $5 != "B/op" { skipped++; next }
        ! numeric( $3 ) { unread++; next }
        {
          # An absent error is absent, not zero. Carrying it as 0 would give a
          # single-fork row a zero-width noise band and let any difference at all
          # read as resolved.
          printf "%s\t%s\t%s\t%s\n", trim( $1 ) $2, $3, ( numeric( $4 ) ? $4 : "-" ), $5
        }
        END {
          if ( skipped )
            printf "# %s: %d rows in other units (rates, counts) not compared\n", source, skipped > "/dev/stderr"
          if ( unread )
            printf "# %s: %d rows with no numeric score not compared\n", source, unread > "/dev/stderr"
        }
      ' | sort -t"$( printf '\t' )" -k1,1
    }

    keyed "${ARGS[0]}" > "$old"
    keyed "${ARGS[1]}" > "$new"

    [[ -s "$old" ]] || { echo "No benchmark rows found in ${ARGS[0]}." >&2; exit 1; }
    [[ -s "$new" ]] || { echo "No benchmark rows found in ${ARGS[1]}." >&2; exit 1; }

    join -t"$( printf '\t' )" -a1 -a2 -e MISSING \
      -o '0,1.2,1.3,1.4,2.2,2.3,2.4' "$old" "$new" \
      | awk -F'\t' -v all="$SHOW_ALL" '
          function nanos ( unit ) {
            return unit == "us/op" ? 1000 : unit == "ms/op" ? 1000000 : unit == "s/op" ? 1000000000 : 1
          }
          {
            name = $1
            if ( $2 == "MISSING" ) {
              added++
              printf "999999\t%-62s %9s %9.3f %9s  ADDED\n", name, "-", $5, "-"
              next
            }
            if ( $5 == "MISSING" ) {
              removed++
              printf "999999\t%-62s %9.3f %9s %9s  REMOVED\n", name, $2, "-", "-"
              next
            }

            # A time is compared in the unit of the old run, the new one
            # converted when the two runs reported in different units.
            o = $2 + 0; n = $5 + 0
            oe = $3; ne = $6
            if ( $4 != $7 ) {
              n *= nanos( $7 ) / nanos( $4 )
              if ( ne != "-" ) ne *= nanos( $7 ) / nanos( $4 )
            }
            if ( o < 0 ) next
            d    = n - o
            ad   = ( d < 0 ) ? -d : d
            total++

            # A zero baseline has no relative change to report — an allocation
            # row that was 0 B/op, for one — so its delta is given absolutely,
            # and a row that moved off zero sorts ahead of every relative change
            # rather than being dropped.
            if ( o > 0 ) {
              rel  = sprintf ( "%+8.1f%%", d / o * 100 )
              size = ad / o
            } else {
              rel  = sprintf ( "%+9.3f", d )
              size = ( ad > 0 ) ? 1000000000 : 0
            }

            # A run that reports no error establishes no noise band, so nothing
            # can be resolved against it at any size. Say that, rather than
            # comparing against an implied zero and calling the result a change.
            if ( oe == "-" || ne == "-" ) {
              unjudged++
              printf "%.6f\t%-62s %9.3f %9.3f %9s  ?\n", size, name, o, n, rel
              next
            }

            oe += 0; ne += 0
            # The uncertainty of a difference between two independent runs is the
            # quadrature sum of their errors, not the linear sum: adding the bars
            # end to end overstates it and buries changes that did resolve.
            band = sqrt ( oe * oe + ne * ne )
            verdict = ( ad > band ) ? ( d > 0 ? "SLOWER" : "faster" ) : "~"
            if ( verdict != "~" ) changed++
            if ( verdict != "~" || all )
              printf "%.6f\t%-62s %9.3f %9.3f %9s  %s\n", \
                ( verdict == "~" ) ? 0 : size, name, o, n, rel, verdict
          }
          END {
            # Keyed below every row, the noise rows --all prints at zero
            # included, so the summary comes last rather than among them.
            if ( ! changed )
              printf "-1\t%s\n", ( unjudged == total && total \
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
      | { printf "%-62s %9s %9s %9s  %s\n" "benchmark" "old" "new" "delta" "verdict"; cat; } \
      | cut -f2- | sed '/^$/d' ;;

esac
