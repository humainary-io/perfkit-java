// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Scope operations.
///
/// Scope is the one lifecycle type the suite measures. The general exclusion of
/// lifecycle from this suite rests on circuit creation being dominated by
/// virtual-thread startup and scheduler effects the harness cannot stabilize
/// (see BENCHMARKS.md). None of that applies here: a scope owns no thread, and
/// creating and closing one is allocation and pointer work whose scores repeat
/// across runs.
///
/// What is measured is the cost of closing a scope, which is a cascade — the
/// scope's registered closures, then everything nested below it. That cascade
/// is the part that changes when the traversal changes, and the part where a
/// regression is otherwise invisible: it lies off the emission path, so no
/// other benchmark in the suite moves when it gets slower.
///
/// The shapes are chosen to separate the costs a cascade can have:
///
/// - **leaf** — the common case. A scope with no children should need no
///   traversal state at all, so this row should not allocate beyond the scope
///   itself.
/// - **children** — one level of nesting, where the descent begins to cost.
/// - **nested** — a chain, where the cost is the depth of the descent rather
///   than the breadth.
///
/// Create and close are measured together. Closing alone would need
/// per-invocation fixture setup, which JMH cannot time reliably at this scale;
/// the paired operation is the honest unit, and the leaf row doubles as the
/// baseline the other two are read against.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class ScopeOps
  implements Substrates {

  private static final int BREADTH = 4;
  private static final int DEPTH   = 8;

  private Name name;

  /// The enclosing scope every measured scope is created within, so that the
  /// rows measure a nested create and close rather than a root one.
  private Scope root;

  ///
  /// CHILDREN: create a scope, nest [#BREADTH] children directly under it,
  /// and close the parent so the cascade reaches all of them.
  ///

  @Benchmark
  public void scope_create_close_children () {

    final var scope =
      root.scope ( name );

    for (
      var i = 0;
      i < BREADTH;
      i++
    ) {
      scope.scope ( name );
    }

    scope.close ();

  }

  ///
  /// LEAF: create a scope with no children and close it.
  ///
  /// The baseline shape, and the one that should carry no traversal cost.
  ///

  @Benchmark
  public void scope_create_close_leaf () {

    root.scope ( name )
      .close ();

  }

  ///
  /// NESTED: create a chain [#DEPTH] deep and close the top, so the cascade
  /// descends rather than fans out.
  ///

  @Benchmark
  public void scope_create_close_nested () {

    final var scope =
      root.scope ( name );

    var current = scope;

    for (
      var i = 0;
      i < DEPTH;
      i++
    ) {
      current =
        current.scope ( name );
    }

    scope.close ();

  }

  ///
  /// CONTROL: create a scope and close it without nesting anything, reading
  /// the scope back so creation cannot be folded away.
  ///
  /// Paired with the leaf row at the same scale. Its score is diagnostic and is
  /// never subtracted from the rows above.
  ///

  @Benchmark
  public void scope_create_close_observed (
    final Blackhole blackhole
  ) {

    final var scope =
      root.scope ( name );

    blackhole.consume (
      scope.subject ()
    );

    scope.close ();

  }

  ///
  /// IDEMPOTENT: close an already-closed scope.
  ///
  /// The repeat close must short-circuit before any traversal; this row is flat
  /// against the leaf row by exactly the cost of that second call.
  ///

  @Benchmark
  public void scope_create_close_twice () {

    final var scope =
      root.scope ( name );

    scope.close ();
    scope.close ();

  }

  @Setup ( Trial )
  public void setup () {

    final var cortex =
      Substrates.cortex ();

    name =
      cortex.name ( "scope" );

    root =
      cortex.scope (
        cortex.name ( "root" )
      );

  }

  @TearDown ( Trial )
  public void tearDown () {

    root.close ();

  }

}
