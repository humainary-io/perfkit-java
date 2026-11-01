// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.serventis.sdk;

import io.humainary.serventis.opt.pool.Resources;
import io.humainary.serventis.sdk.SignMap;
import io.humainary.serventis.sdk.Statuses;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import static io.humainary.serventis.opt.pool.Resources.Sign.GRANT;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for SignMap.apply() — ordinal-indexed sign-to-sign translation lookup.
///
/// Measures the per-call cost of translating a source sign into a target sign through
/// a pre-computed map.
///


@SuppressWarnings ( "MethodMayBeStatic" )
@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class SignMapOps {

  private static final int BATCH_SIZE = 10000;

  // A power-of-two input table, cycling through every source sign, so the varied lookup is
  // indexed with a mask: a remainder by the sign count would cost more than the array load
  // being measured.
  private static final int INPUTS     = 64;
  private static final int INPUT_MASK = INPUTS - 1;

  private static final SignMap < Resources.Sign, Statuses.Sign > MAP =
    Resources.SIGNS.map (
      sign -> switch ( sign ) {
        case GRANT -> Statuses.Sign.STABLE;
        case DENY, TIMEOUT -> Statuses.Sign.DEGRADED;
        default -> null;
      }
    );

  private Resources.Sign[] inputs;
  private Statuses.Sign[]  expected;
  private int              offset;

  ///
  /// Benchmark a single translation lookup.
  ///

  @Benchmark
  public Statuses.Sign apply_single () {

    return
      MAP.apply (
        GRANT
      );

  }

  ///
  /// Benchmark varied lookups cycling across all source signs.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void apply_varied_batch (
    final Blackhole blackhole
  ) {

    final var inputs =
      this.inputs;

    final var offset =
      this.offset++;

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      blackhole.consume (
        MAP.apply (
          inputs[( offset + i ) & INPUT_MASK]
        )
      );
    }

  }

  ///
  /// Matching loop, indexing, and Blackhole control for `apply_varied_batch`.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void apply_varied_control_batch (
    final Blackhole blackhole
  ) {

    final var expected =
      this.expected;

    final var offset =
      this.offset++;

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      blackhole.consume (
        expected[( offset + i ) & INPUT_MASK]
      );
    }

  }

  @Setup ( Trial )
  public void setupTrial () {

    final var signs =
      Resources.Sign.values ();

    inputs =
      new Resources.Sign[INPUTS];

    expected =
      new Statuses.Sign[INPUTS];

    for (
      var i = 0;
      i < INPUTS;
      i++
    ) {

      inputs[i] =
        signs[i % signs.length];

      expected[i] =
        MAP.apply (
          inputs[i]
        );

    }

  }

}
