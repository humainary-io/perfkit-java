// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Conduit operations.
///
/// Measures conduit percept lookup (the percept operations from the Pool
/// interface) and the per-percept wrapping recipes applied lazily on lookup.
/// Conduits cache percepts by name, providing consistent identity for named
/// channels, so lookup recurs with every named channel touched.
///
/// Neither subscription nor `pool(...)` construction is measured. Both are
/// wiring-time work paid once per configuration, and the recurring half of a
/// derived pool — applying the recipe lazily on `get(name)` — is measured by
/// `PoolOps.derived_get_varied_batch`.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class ConduitOps
  implements Substrates {

  private static final String NAME_STR   = "test";
  private static final int    BATCH_SIZE = 10000;

  // A power-of-two pool keeps the varied-lookup index cheap: an integer remainder
  // would cost more than the lookup it is meant to vary.
  private static final int POOL_SIZE  = 128;
  private static final int INDEX_MASK = POOL_SIZE - 1;

  private Cortex              cortex;
  private Circuit             circuit;
  private Conduit < Integer > conduit;
  private Name                name;
  private Scope               scope;
  private Name[]              names;
  private int                 index;

  ///
  /// Benchmark percept retrieval via percept(Name) - Pool interface.
  ///

  @Benchmark
  public Pipe < Integer > get_by_name () {

    return
      conduit.get (
        name
      );

  }

  ///
  /// Benchmark percept retrieval via percept(Substrate) - Pool interface.
  ///

  @Benchmark
  public Pipe < Integer > get_by_substrate () {

    return
      conduit.get (
        scope
      );

  }

  ///
  /// Benchmark cached percept retrieval (pooling behavior). Two lookups per
  /// invocation, so the score is normalized over both — without that the row
  /// reads as double the single-lookup cost measured by [#get_by_name()].
  ///

  @Benchmark
  @OperationsPerInvocation ( 2 )
  public boolean get_cached () {

    final var
      first =
      conduit.get (
        name
      );

    final var
      second =
      conduit.get (
        name
      );

    return
      first == second;

  }

  ///
  /// Benchmark percept retrieval with varied names (tests hashCode uniqueness).
  /// This cycles through different Name objects to stress hash-based lookup
  /// in a populated map, measuring the benefit of collision-free hashing.
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
        conduit.get (
          localNames[localIndex++ & INDEX_MASK]
        )
      );
    }

    index = localIndex;

  }

  ///
  /// Loop-and-consume baseline for [#get_varied_batch].
  ///
  /// Measured as a benchmark in its own right; its score is never subtracted
  /// from the target's.
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

    circuit =
      cortex.circuit ();

    conduit =
      circuit.conduit (
        Integer.class
      );

    // Pre-populate conduit with all percepts for varied lookup benchmarks
    for ( final var n : names ) {

      conduit.get ( n );

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

    scope =
      cortex.scope (
        name
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

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

  @TearDown ( Trial )
  public void tearDownTrial () {

    scope.close ();

  }

}
