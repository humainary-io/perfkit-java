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
          inputs[( offset + i ) % inputs.length]
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
        expected[( offset + i ) % expected.length]
      );
    }

  }

  @Setup ( Trial )
  public void setupTrial () {

    inputs =
      Resources.Sign.values ();

    expected =
      new Statuses.Sign[inputs.length];

    for (
      var i = 0;
      i < inputs.length;
      i++
    ) {
      expected[i] =
        MAP.apply (
          inputs[i]
        );
    }

  }

}
