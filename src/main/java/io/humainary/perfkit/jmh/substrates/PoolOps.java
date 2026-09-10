// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for root Pool operations.
///
/// Measures cached name-based lookup on a root pool and on a derived pool. The
/// factory function is the identity over names so the measured cost is the
/// pool's own lookup path, directly comparable to the conduit percept lookups
/// in [ConduitOps].
///
/// Pool construction is not measured — a pool is built once at wiring time,
/// whereas lookup recurs with every name resolved through it.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class PoolOps
  implements Substrates {

  private static final String NAME_STR   = "test";
  private static final int    BATCH_SIZE = 10000;

  // A power-of-two pool keeps the varied-lookup index cheap: an integer remainder
  // would cost more than the lookup it is meant to vary.
  private static final int POOL_SIZE  = 128;
  private static final int INDEX_MASK = POOL_SIZE - 1;

  private Cortex        cortex;
  private Pool < Name > pool;
  private Pool < Name > derived;
  private Name          name;
  private Name[]        names;
  private int           index;

  ///
  /// Benchmark varied lookup through a derived pool.
  ///
  /// Comparison target for [#get_varied_batch]: the difference is the cost the
  /// derived view adds to a populated lookup. Shares the control of the root
  /// pool, which is why it carries the same normalization.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void derived_get_varied_batch (
    final Blackhole blackhole
  ) {

    final var localNames = names;
    var localIndex = index;

    for ( var i = 0; i < BATCH_SIZE; i++ ) {
      blackhole.consume (
        derived.get (
          localNames[localIndex++ & INDEX_MASK]
        )
      );
    }

    index = localIndex;

  }

  ///
  /// Benchmark cached value retrieval via get(Name).
  ///

  @Benchmark
  public Name get_by_name () {

    return
      pool.get (
        name
      );

  }

  ///
  /// Benchmark cached value retrieval with varied names (populated map lookup).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void get_varied_batch (
    final Blackhole blackhole
  ) {

    final var localNames = names;
    var localIndex = index;

    for ( var i = 0; i < BATCH_SIZE; i++ ) {
      blackhole.consume (
        pool.get (
          localNames[localIndex++ & INDEX_MASK]
        )
      );
    }

    index = localIndex;

  }

  ///
  /// Loop-and-consume baseline for [#get_varied_batch] and
  /// [#derived_get_varied_batch].
  ///
  /// Measured as a benchmark in its own right; its score is never subtracted
  /// from a target's.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void get_varied_control_batch (
    final Blackhole blackhole
  ) {

    final var localNames = names;
    var localIndex = index;

    for ( var i = 0; i < BATCH_SIZE; i++ ) {
      blackhole.consume (
        localNames[localIndex++ & INDEX_MASK]
      );
    }

    index = localIndex;

  }

  @Setup ( Iteration )
  public void setupIteration () {

    pool =
      cortex.pool (
        n -> n
      );

    derived =
      pool.pool (
        n -> n
      );

    // Pre-populate both views for cached lookup benchmarks
    pool.get ( name );

    for ( final var n : names ) {

      pool.get ( n );
      derived.get ( n );

    }

    // Reset index for varied benchmarks
    index = 0;

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    name =
      cortex.name (
        NAME_STR
      );

    // Create array of distinct names for varied lookup benchmarks
    names =
      new Name[POOL_SIZE];

    for ( var i = 0; i < POOL_SIZE; i++ ) {

      names[i] =
        cortex.name (
          "name" + i
        );

    }

  }

}
