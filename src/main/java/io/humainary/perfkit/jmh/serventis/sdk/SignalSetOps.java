// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.serventis.sdk;

import io.humainary.serventis.sdk.SignalSet;
import io.humainary.serventis.sdk.Situations;
import io.humainary.serventis.sdk.Situations.Dimension;
import io.humainary.serventis.sdk.Situations.Sign;
import io.humainary.serventis.sdk.Situations.Signal;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import static io.humainary.serventis.sdk.Situations.Dimension.CONSTANT;
import static io.humainary.serventis.sdk.Situations.Sign.NORMAL;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for SignalSet.get() operations.
///
/// Measures retrieval of pre-allocated signals from the Sign × Dimension Cartesian product.
///


@SuppressWarnings ( "MethodMayBeStatic" )
@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class SignalSetOps {

  private static final Sign[]      SIGNS      = Sign.values ();
  private static final Dimension[] DIMENSIONS = Dimension.values ();
  private static final int         BATCH_SIZE = 10000;

  private static final SignalSet < Sign, Dimension, Signal > SIGNALS =
    Situations.SIGNS.signals (
      Situations.DIMENSIONS,
      Signal::new
    );

  // A power-of-two table cycling through every sign × dimension pair, so the varied lookup is
  // indexed with a mask: a remainder and a division per lookup would cost more than the array
  // load being measured.

  private static final int         PAIRS           = 64;
  private static final int         PAIR_MASK       = PAIRS - 1;
  private static final Sign[]      PAIR_SIGNS      = new Sign[PAIRS];
  private static final Dimension[] PAIR_DIMENSIONS = new Dimension[PAIRS];
  private static final Signal[]    PAIR_SIGNALS    = new Signal[PAIRS];

  static {

    for ( var k = 0; k < PAIRS; k++ ) {

      final var pair =
        k % ( SIGNS.length * DIMENSIONS.length );

      PAIR_SIGNS[k] =
        SIGNS[pair % SIGNS.length];

      PAIR_DIMENSIONS[k] =
        DIMENSIONS[pair / SIGNS.length];

      PAIR_SIGNALS[k] =
        SIGNALS.get (
          PAIR_SIGNS[k],
          PAIR_DIMENSIONS[k]
        );

    }

  }

  ///
  /// Benchmark single signal lookup.
  ///

  @Benchmark
  public Signal get_single () {

    return
      SIGNALS.get (
        NORMAL,
        CONSTANT
      );

  }

  ///
  /// Benchmark varied signal lookups across different signs and dimensions.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void get_varied_batch (
    final Blackhole blackhole
  ) {

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {

      final var k =
        i & PAIR_MASK;

      blackhole.consume (
        SIGNALS.get (
          PAIR_SIGNS[k],
          PAIR_DIMENSIONS[k]
        )
      );

    }

  }

  ///
  /// Matching loop, indexing, and Blackhole control for `get_varied_batch`: the
  /// same walk over the pair table, consuming the precomputed signal.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void get_varied_control_batch (
    final Blackhole blackhole
  ) {

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {

      blackhole.consume (
        PAIR_SIGNALS[i & PAIR_MASK]
      );

    }

  }

  ///
  /// Benchmark worst-case lookup (last sign, last dimension).
  ///

  @Benchmark
  public Signal get_worst_case () {

    return
      SIGNALS.get (
        SIGNS[SIGNS.length - 1],
        DIMENSIONS[DIMENSIONS.length - 1]
      );

  }

}
