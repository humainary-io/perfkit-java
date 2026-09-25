// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.serventis.sdk;

import io.humainary.serventis.sdk.SignalMap;
import io.humainary.serventis.sdk.SignalSet;
import io.humainary.serventis.sdk.Situations;
import io.humainary.serventis.sdk.Statuses;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import static io.humainary.serventis.sdk.Situations.Dimension.CONSTANT;
import static io.humainary.serventis.sdk.Situations.Dimension.VOLATILE;
import static io.humainary.serventis.sdk.Situations.Sign.NORMAL;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for SignalMap lookups — ordinal-indexed signal projection cache.
///
/// Measures direct sign/dimension lookup and Function-style lookup from a signal instance.
///

@SuppressWarnings ( "MethodMayBeStatic" )
@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class SignalMapOps {

  private static final Situations.Sign[]      SIGNS      = Situations.Sign.values ();
  private static final Situations.Dimension[] DIMENSIONS = Situations.Dimension.values ();
  private static final int                    BATCH_SIZE = 10000;

  private static final SignalSet <
    Situations.Sign,
    Situations.Dimension,
    Situations.Signal
    > SIGNALS =
    Situations.SIGNS.signals (
      Situations.DIMENSIONS,
      Situations.Signal::new
    );

  private static final SignalMap <
    Situations.Sign,
    Situations.Dimension,
    Situations.Signal,
    Statuses.Sign
    > MAP =
    SIGNALS.map (
      signal -> switch ( signal.sign () ) {
        case NORMAL -> Statuses.Sign.STABLE;
        case WARNING -> signal.dimension () == VOLATILE
                        ? Statuses.Sign.DIVERGING
                        : Statuses.Sign.DEGRADED;
        case CRITICAL -> Statuses.Sign.DEFECTIVE;
      }
    );

  ///
  /// Benchmark Function-style lookup from a signal instance.
  ///

  @Benchmark
  public Statuses.Sign apply_single () {

    return
      MAP.apply (
        SIGNALS.get (
          NORMAL,
          CONSTANT
        )
      );

  }

  ///
  /// Benchmark batched Function-style lookups from varied signal instances.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void apply_varied_batch (
    final Blackhole blackhole
  ) {

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {

      blackhole.consume (
        MAP.apply (
          SIGNALS.get (
            SIGNS[i % SIGNS.length],
            DIMENSIONS[i % DIMENSIONS.length]
          )
        )
      );

    }

  }

  ///
  /// Benchmark direct lookup by sign and dimension pair.
  ///

  @Benchmark
  public Statuses.Sign get_single () {

    return
      MAP.get (
        NORMAL,
        CONSTANT
      );

  }

  ///
  /// Benchmark batched direct lookups cycling across signs and dimensions.
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

      blackhole.consume (
        MAP.get (
          SIGNS[i % SIGNS.length],
          DIMENSIONS[i % DIMENSIONS.length]
        )
      );

    }

  }

}
