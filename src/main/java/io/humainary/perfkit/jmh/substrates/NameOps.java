// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Name operations.
///
/// Measures performance of name creation, parsing, and hierarchy operations.
/// Names are interned and cached, providing identity-based equality for equivalent hierarchies.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class NameOps
  implements Substrates {

  private static final String FIRST              = "first";
  private static final String SECOND             = "second";
  private static final String THIRD              = "third";
  private static final String FIRST_SECOND_THIRD = "first.second.third";
  private static final int    BATCH_SIZE         = 1000;
  private static final int    LARGE_BATCH_SIZE   = 10000;
  private static final int    VARIED_INPUT_COUNT = 256;
  // A power-of-two width keeps the varied-lookup index cheap: an integer remainder
  // would cost more than the lookup it is meant to vary.
  private static final int    CHILD_COUNT        = 16;
  private static final int    CHILD_MASK         = CHILD_COUNT - 1;
  private static final int    WIDE_CHILD_COUNT   = 128;

  private Cortex            cortex;
  private Name              firstName;
  private Name              deepName;
  private Name              rootName;
  private Name              unrelatedName;
  private List < String >   nameParts;
  private List < TestEnum > enumList;
  private Name[]            variedNames;
  private int[]             variedDepths;
  private int               variedOffset;
  private Name              parentName;
  private String[]          childParts;
  private VariedEnum[]      variedConstants;
  private int               childIndex;
  private Name              wideName;
  private String            oldestPart;

  ///
  /// NAME CONSTRUCTION: Deep chaining (5 levels).
  ///

  @Benchmark
  public Name name_chained_deep () {

    return
      cortex.name ( "a" )
        .name ( "b" )
        .name ( "c" )
        .name ( "d" )
        .name ( "e" );

  }

  ///
  /// Benchmark name creation by chaining.
  ///

  @Benchmark
  public Name name_chaining () {

    return
      cortex.name (
        FIRST
      ).name (
        SECOND
      ).name (
        THIRD
      );

  }

  ///
  /// One segment looked up again and again under a parent holding 128 children,
  /// the segment being the oldest of them.
  ///
  /// Children are found by a scan from the newest, so the oldest is the last one
  /// reached. Read against [#name_child_varied_batch]: this row prices what a
  /// repeated lookup costs when nothing remembers where it was found last.
  ///

  @Benchmark
  public Name name_child_oldest_wide () {

    return
      wideName.name (
        oldestPart
      );

  }

  ///
  /// Child lookups under one parent, cycling through 16 segments, so no two
  /// consecutive lookups ask for the same child.
  ///
  /// The segments are the instances the children were created with, as a caller
  /// passing constants would supply them.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void name_child_varied_batch (
    final Blackhole blackhole
  ) {

    variedChildren (
      blackhole
    );

  }

  ///
  /// [#name_child_varied_batch] from four threads sharing one parent.
  ///
  /// Every other name row runs on the JMH thread alone, so none of them can show
  /// what lookups from several callers cost one another. Any state a lookup
  /// writes on the way through is written from every core.
  ///

  @Benchmark
  @Threads ( 4 )
  @OperationsPerInvocation ( BATCH_SIZE )
  public void name_child_varied_contended_batch (
    final Blackhole blackhole
  ) {

    variedChildren (
      blackhole
    );

  }

  ///
  /// Loop-and-consume baseline for [#name_child_varied_batch] and
  /// [#name_enum_varied_batch].
  ///
  /// Measured as a benchmark in its own right; its score is never subtracted
  /// from the target's.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void name_child_varied_control_batch (
    final Blackhole blackhole
  ) {

    final var parts = childParts;
    var index = childIndex;

    for ( var i = 0; i < BATCH_SIZE; i++ ) {
      blackhole.consume (
        parts[index++ & CHILD_MASK]
      );
    }

    childIndex = index;

  }

  ///
  /// TRAVERSAL: Compare names hierarchically.
  ///

  @Benchmark
  public int name_compare () {

    return
      deepName.compareTo (
        firstName
      );

  }

  ///
  /// TRAVERSAL: Measure depth() calculation.
  ///

  @Benchmark
  public int name_depth () {

    return
      deepName.depth ();

  }

  @Benchmark
  @OperationsPerInvocation ( LARGE_BATCH_SIZE )
  public int name_depth_control_batch_10k () {

    return
      variedDepthControl (
        LARGE_BATCH_SIZE
      );

  }

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public int name_depth_control_batch_1k () {

    return
      variedDepthControl (
        BATCH_SIZE
      );

  }

  @Benchmark
  @OperationsPerInvocation ( LARGE_BATCH_SIZE )
  public int name_depth_varied_batch_10k () {

    return
      variedDepth (
        LARGE_BATCH_SIZE
      );

  }

  ///
  /// Runtime-varying depth workload and its loop/index/aggregate control.
  /// The two batch sizes are intentionally separate JMH methods so
  /// `@OperationsPerInvocation` remains exact.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public int name_depth_varied_batch_1k () {

    return
      variedDepth (
        BATCH_SIZE
      );

  }

  ///
  /// TRAVERSAL: Navigate to parent via enclosure().
  ///

  @Benchmark
  public Name name_enclosure () {

    return
      deepName
        .enclosure ()
        .orElse (
          null
        );

  }

  ///
  /// Names of enum constants, cycling through all 16 of one enum.
  ///
  /// Each constant's name is a child of its enum's name, so this is
  /// [#name_child_varied_batch] reached the way a caller converting constants
  /// per request reaches it.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void name_enum_varied_batch (
    final Blackhole blackhole
  ) {

    variedConstants (
      blackhole
    );

  }

  ///
  /// [#name_enum_varied_batch] from four threads converting the same enum.
  ///

  @Benchmark
  @Threads ( 4 )
  @OperationsPerInvocation ( BATCH_SIZE )
  public void name_enum_varied_contended_batch (
    final Blackhole blackhole
  ) {

    variedConstants (
      blackhole
    );

  }

  ///
  /// NAME CONSTRUCTION: From enum constant.
  ///

  @Benchmark
  public Name name_from_enum () {

    return
      cortex.name (
        TestEnum.SECOND
      );

  }

  ///
  /// NAME CONSTRUCTION: From Iterable<>.
  ///

  @Benchmark
  public Name name_from_iterable () {

    return
      cortex.name (
        nameParts
      );

  }

  ///
  /// NAME CONSTRUCTION: From Iterator<>.
  ///

  @Benchmark
  public Name name_from_iterator () {

    return
      cortex.name (
        nameParts.iterator ()
      );

  }

  ///
  /// NAME CONSTRUCTION: From Iterable with mapper function.
  ///

  @Benchmark
  public Name name_from_mapped_iterable () {

    return
      cortex.name (
        enumList,
        Enum::name
      );

  }

  ///
  /// NAME CONSTRUCTION: Append Name to existing Name.
  ///

  @Benchmark
  public Name name_from_name () {

    return
      firstName.name (
        cortex.name (
          SECOND
        )
      );

  }

  ///
  /// Benchmark name creation from a string.
  ///

  @Benchmark
  public Name name_from_string () {

    return
      cortex.name (
        FIRST
      );

  }

  ///
  /// HASH: Measure hashCode() computation.
  ///

  @Benchmark
  public int name_hashCode () {

    return
      deepName.hashCode ();

  }

  ///
  /// INTERNING: Chained construction identity.
  ///

  @Benchmark
  public boolean name_interning_chained () {

    final var
      parsed =
      cortex.name (
        FIRST_SECOND_THIRD
      );

    final var
      chained =
      cortex.name ( FIRST )
        .name ( SECOND )
        .name ( THIRD );

    return
      parsed == chained;

  }

  ///
  /// INTERNING: Identity check for same path.
  ///

  @Benchmark
  public boolean name_interning_same_path () {

    final var
      name1 =
      cortex.name (
        FIRST_SECOND_THIRD
      );

    final var
      name2 =
      cortex.name (
        FIRST_SECOND_THIRD
      );

    return
      name1 == name2;

  }

  ///
  /// INTERNING: Verify segment reuse in chaining.
  ///

  @Benchmark
  public boolean name_interning_segments () {

    final var
      first1 =
      cortex.name (
        FIRST
      );

    final var
      first2 =
      cortex.name (
          FIRST
        ).name (
          SECOND
        ).enclosure ()
        .orElse (
          null
        );

    return
      first1 == first2;

  }

  ///
  /// TRAVERSAL: Iterate through hierarchy.
  ///

  @Benchmark
  public long name_iterate_hierarchy () {

    long
      count =
      0;

    for (
      final var
        _ :
      deepName
    ) {
      count++;
    }

    return
      count;

  }

  ///
  /// Benchmark name creation by parsing a dotted string.
  ///

  @Benchmark
  public Name name_parsing () {

    return
      cortex.name (
        FIRST_SECOND_THIRD
      );

  }

  ///
  /// TRAVERSAL: Generate path string.
  ///

  @Benchmark
  public CharSequence name_path_generation () {

    return
      deepName.path (
        '.'
      );

  }

  ///
  /// TRAVERSAL: Check if name is within enclosure (true case, full depth traversal).
  ///

  @Benchmark
  public boolean name_within () {

    return
      deepName.within (
        rootName
      );

  }

  ///
  /// TRAVERSAL: Check if name is within unrelated name (false case, full depth traversal).
  ///

  @Benchmark
  public boolean name_within_false () {

    return
      deepName.within (
        unrelatedName
      );

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    firstName =
      cortex.name (
        FIRST
      );

    rootName =
      cortex.name (
        "root"
      );

    deepName =
      rootName
        .name ( "level1" )
        .name ( "level2" )
        .name ( "level3" )
        .name ( "level4" );

    unrelatedName =
      cortex.name (
        "unrelated"
      );

    nameParts =
      List.of (
        FIRST,
        SECOND,
        THIRD
      );

    enumList =
      List.of (
        TestEnum.FIRST,
        TestEnum.SECOND,
        TestEnum.THIRD
      );

    variedNames =
      new Name[VARIED_INPUT_COUNT];

    variedDepths =
      new int[VARIED_INPUT_COUNT];

    for (
      var i = 0;
      i < VARIED_INPUT_COUNT;
      i++
    ) {
      var variedName =
        cortex.name (
          "varied-" + i
        );

      for (
        var depth = 0;
        depth < ( i & 7 );
        depth++
      ) {
        variedName =
          variedName.name (
            "level-" + depth
          );
      }

      variedNames[i] =
        variedName;

      variedDepths[i] =
        variedName.depth ();
    }

    parentName =
      cortex.name (
        "parent"
      );

    childParts =
      new String[CHILD_COUNT];

    for (
      var i = 0;
      i < CHILD_COUNT;
      i++
    ) {
      childParts[i] =
        "child-" + i;

      parentName.name (
        childParts[i]
      );
    }

    variedConstants =
      VariedEnum.values ();

    for (
      final var constant :
      variedConstants
    ) {
      cortex.name (
        constant
      );
    }

    wideName =
      cortex.name (
        "wide"
      );

    for (
      var i = 0;
      i < WIDE_CHILD_COUNT;
      i++
    ) {
      final var part =
        "wide-" + i;

      if ( i == 0 ) {
        oldestPart =
          part;
      }

      wideName.name (
        part
      );
    }

  }

  private void variedChildren (
    final Blackhole blackhole
  ) {

    final var parent = parentName;
    final var parts = childParts;
    var index = childIndex;

    for ( var i = 0; i < BATCH_SIZE; i++ ) {
      blackhole.consume (
        parent.name (
          parts[index++ & CHILD_MASK]
        )
      );
    }

    childIndex = index;

  }

  private void variedConstants (
    final Blackhole blackhole
  ) {

    final var constants = variedConstants;
    var index = childIndex;

    for ( var i = 0; i < BATCH_SIZE; i++ ) {
      blackhole.consume (
        cortex.name (
          constants[index++ & CHILD_MASK]
        )
      );
    }

    childIndex = index;

  }

  private int variedDepth (
    final int operations
  ) {

    var result =
      0;

    final var offset =
      variedOffset++;

    final var names =
      variedNames;

    for (
      var i = 0;
      i < operations;
      i++
    ) {
      result +=
        names[( offset + i ) & ( VARIED_INPUT_COUNT - 1 )].depth ();
    }

    return
      result;

  }

  private int variedDepthControl (
    final int operations
  ) {

    var result =
      0;

    final var offset =
      variedOffset++;

    final var depths =
      variedDepths;

    for (
      var i = 0;
      i < operations;
      i++
    ) {
      result +=
        depths[( offset + i ) & ( VARIED_INPUT_COUNT - 1 )];
    }

    return
      result;

  }

  private enum TestEnum {
    FIRST,
    SECOND,
    THIRD
  }

  /// As many constants as `CHILD_COUNT`, which the varied enum rows index by mask.
  private enum VariedEnum {
    C0, C1, C2, C3, C4, C5, C6, C7,
    C8, C9, C10, C11, C12, C13, C14, C15
  }

}
