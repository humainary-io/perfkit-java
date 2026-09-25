// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for cyclic emission patterns.
///
/// Measures the performance of self-reinforcing emission cycles where a subscriber
/// re-registers the same pipe on each emission, creating a feedback loop that
/// continues until a limit is reached.
///
/// This benchmark tests:
/// - Transit queue priority behavior (cascading emissions complete before next external input)
/// - Stack safety for deeply cascading chains (queue-based, not recursive)
/// - Neural-like signal propagation dynamics
///
/// The pattern mirrors the exp/Cycles.java example.
///
/// [#CYCLE_LIMIT] is 10000 for the same reason every batched emission benchmark
/// in the suite is: each invocation ends in one `await()`, and the caller-to-worker
/// rendezvous has to be spread over enough operations to disappear. An order of
/// magnitude lower, that rendezvous dominated the score, and the gap between the
/// shallow and deep forms was the difference between their two normalizations
/// rather than anything about queue depth.
///
/// The deep form still runs ten times the cascade, so it names that scale: its
/// operations carry the same single drain, so the drain's per-operation share is
/// a tenth of what a shallow form carries. Read the margin by which the deep form
/// undercuts the shallow ones as that difference and not as a depth effect — the
/// linear scaling this benchmark exists to check is what the two agreeing *after*
/// that correction means. Subtract the two normalizations from a run in hand
/// rather than from a figure quoted here, which would be one provider's.
///
/// Unlike every other class in the suite, these benchmarks build their conduit,
/// pool, and subscription inside the measured body, and that is deliberate: the
/// cycle is terminated by a stateful `limit` fiber which is spent once it has
/// passed its quota, so the topology cannot be reused across invocations. The
/// alternative — a guard over an externally reset counter — would put a counter
/// read on every one of the [#CYCLE_LIMIT] cascade steps and change what the
/// benchmark measures, to remove a fixture whose allocation, amortized over a
/// batch this size, rounds to a fraction of a byte per operation and less again
/// in the deep form. Per-iteration scores oscillate rather than
/// climb, so the subscriptions retained across an iteration are not drifting the
/// measurement. Read the allocation figure as the cycle's own plus that
/// constant.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class CyclicOps
  implements Substrates {

  private static final int             CYCLE_LIMIT = 10000;
  private static final Object          PAYLOAD     = new Object ();
  // Transit emissions counted by the circuit context, held off this state object.
  private final        Tally           emissions   = new Tally ();
  private              Cortex          cortex;
  private              Name            pipesName;
  private              Name            cyclicName;
  private              Circuit         circuit;
  private              Pipe < Object > prime;

  ///
  /// Two consecutive ingress cascades: a two-target fan-out, then a cycle.
  /// Transit quiesces between them, so the cyclic seed must find the slot open.
  /// No extra await or topology job is inserted: in the previous valve, an
  /// empty drain could repair stale admission state and mask this regression.
  /// This measures recovery across ingress jobs; contraction to a cycle within
  /// one transit phase requires a separate workload.
  /// Normalized per cyclic delivery; the two priming deliveries are also verified.
  ///

  @Benchmark
  @OperationsPerInvocation ( CYCLE_LIMIT * 10 )
  public void cyclic_emit_after_fanout_100k (
    final TerminalVerification verification
  ) {

    verification.expect (
      emissions,
      CYCLE_LIMIT * 10L + 2
    );

    final var conduit =
      circuit.conduit ();

    final var pool =
      conduit.pool (
        countedLimit (
          CYCLE_LIMIT * 10L
        )
      );

    conduit.subscribe (
      circuit.subscriber (
        pipesName,
        pool
      )
    );

    final var pipe =
      pool.get ( cyclicName );

    prime.emit ( PAYLOAD );
    pipe.emit ( PAYLOAD );

    circuit.await ();

  }

  ///
  /// Benchmark deep cyclic emission chain with await.
  ///
  /// Tests performance with longer cascading chains (10x limit) to validate
  /// that queue-based processing scales linearly. Normalized over 100000
  /// operations rather than the suite's 10000, which the name carries because
  /// a reader comparing it to the shallow forms is comparing across two
  /// normalizations.
  ///

  @Benchmark
  @OperationsPerInvocation ( CYCLE_LIMIT * 10 )
  public void cyclic_emit_deep_100k (
    final TerminalVerification verification
  ) {

    verification.expect (
      emissions,
      CYCLE_LIMIT * 10L
    );

    final var conduit =
      circuit.conduit ();

    final var pool =
      conduit.pool (
        countedLimit (
          CYCLE_LIMIT * 10L
        )
      );

    conduit.subscribe (
      circuit.subscriber (
        pipesName,
        pool
      )
    );

    pool
      .get ( cyclicName )
      .emit ( PAYLOAD );

    circuit.await ();

  }

  ///
  /// Benchmark a complete cyclic emission drain through direct pool registration.
  ///
  /// Measures conduit creation, subscription, initial admission, and all
  /// [#CYCLE_LIMIT] transit emissions. The invocation does not return until the
  /// circuit has drained, so the operation count and timing boundary agree.
  ///

  @Benchmark
  @OperationsPerInvocation ( CYCLE_LIMIT )
  public void cyclic_emit_direct (
    final TerminalVerification verification
  ) {

    verification.expect (
      emissions,
      CYCLE_LIMIT
    );

    final Conduit < Object > conduit =
      circuit.conduit ();

    final var pool =
      conduit.pool (
        countedLimit (
          CYCLE_LIMIT
        )
      );

    conduit.subscribe (
      circuit.subscriber (
        pipesName,
        pool
      )
    );

    pool.get (
      cyclicName
    ).emit (
      PAYLOAD
    );

    circuit.await ();

  }

  ///
  /// Benchmark full cyclic emission chain with await.
  ///
  /// Creates a self-reinforcing cycle where each emission triggers
  /// the subscriber to re-register the same pipe, causing another
  /// emission. The cycle continues until the limit flow operator
  /// stops propagation after CYCLE_LIMIT emissions.
  ///

  @Benchmark
  @OperationsPerInvocation ( CYCLE_LIMIT )
  public void cyclic_emit_registrar (
    final TerminalVerification verification
  ) {

    verification.expect (
      emissions,
      CYCLE_LIMIT
    );

    final var conduit =
      circuit.conduit ();

    final var pool =
      conduit.pool (
        countedLimit (
          CYCLE_LIMIT
        )
      );

    conduit.subscribe (
      circuit.subscriber (
        pipesName,
        ( subject, registrar ) ->
          registrar.register (
            pool.get (
              subject
            )
          )
      )
    );

    pool.get (
      cyclicName
    ).emit (
      PAYLOAD
    );

    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    emissions.take ();

    circuit =
      cortex.circuit ();

    final var target =
      circuit.pipe (
        _ -> emissions.increment ()
      );

    prime =
      circuit.pipe (
        value -> {
          target.emit ( value );
          target.emit ( value );
        }
      );

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    pipesName =
      cortex.name (
        "pipes"
      );

    cyclicName =
      cortex.name (
        "cyclic"
      );

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.await ();
    circuit.close ();

  }

  private Fiber < Object > countedLimit (
    final long limit
  ) {

    final var tally =
      emissions;

    return
      cortex.fiber ()
        .limit ( limit )
        .peek ( _ -> tally.increment () );

  }

}
