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

    // Cycle through different signs and dimensions

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {

      blackhole.consume (
        SIGNALS.get (
          SIGNS[i % SIGNS.length],
          DIMENSIONS[i % DIMENSIONS.length]
        )
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
