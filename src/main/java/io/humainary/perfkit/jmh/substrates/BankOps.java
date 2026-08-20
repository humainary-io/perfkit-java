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
/// Benchmark for Bank operations.
///
/// Measures the cached lookup cost of `Bank.get(Name)` — the hot path
/// after initial materialization. The cold materialization path is not
/// benchmarked here as it is a one-time setup cost per name.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class BankOps
  implements Substrates {

  private static final int BATCH_SIZE = 10000;

  // A power-of-two pool keeps the varied-lookup index cheap: an integer remainder
  // would cost more than the lookup it is meant to vary.
  private static final int POOL_SIZE  = 128;
  private static final int INDEX_MASK = POOL_SIZE - 1;

  private Cortex                       cortex;
  private Circuit                      circuit;
  private Bank < Conduit < Integer > > bank;
  private Name                         name;
  private Name[]                       names;
  private int                          index;

  ///
  /// Benchmark cached conduit retrieval by name (single-slot cache path).
  ///
  /// Measures the steady-state cost of `Bank.get(Name)` for a name that
  /// has already been materialized. Exercises the Maps.Map single-slot
  /// cache fast path.
  ///

  @Benchmark
  public Conduit < Integer > get_cached () {

    return
      bank.get (
        name
      );

  }

  ///
  /// Benchmark cached conduit retrieval across varied names (CHM path).
  ///
  /// Cycles through [#POOL_SIZE] pre-materialized names, stressing the
  /// ConcurrentHashMap lookup path rather than the single-slot cache.
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
        bank.get (
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

    bank =
      circuit.bank (
        Integer.class
      );

    bank.get ( name );

    for ( final var n : names ) {
      bank.get ( n );
    }

    index = 0;

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex = Substrates.cortex ();

    name =
      cortex.name (
        "bank.conduit"
      );

    names = new Name[POOL_SIZE];

    for ( var i = 0; i < POOL_SIZE; i++ ) {

      names[i] =
        cortex.name (
          "bank.conduit." + i
        );

    }

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

}
