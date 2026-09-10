// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import java.time.Duration;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Fiber per-emission operators.
///
/// Measures the hot-path cost of materialized Fiber operator chains attached to
/// a Pipe via `flow.fiber(fiber).pipe(pipe)`. Each benchmark isolates a single
/// operator (or a small combination) against the plain async pipe baseline.
///
/// ## Benchmark Categories
///
/// 1. **Identity**: Forwarder-only fiber (baseline for attachment overhead)
/// 2. **Stateless**: guard, peek, route
/// 3. **Stateful**: diff, heartbeat, limit, every, every(Duration), chance, reduce, distinct, streak
/// 4. **Composed**: guard + diff (representative chain), when
/// 5. **Attachment**: `fiber.pipe(target)` materialization cost. A pooled
///    conduit materializes a fresh pipe per subject, recurring with the
///    subject population. (One-time wiring construction is not benchmarked).
/// 6. **Bound**: `above(Comparator, E)` vs `above(E)` natural-order overload
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class FiberOps
  implements Substrates {

  private static final String    NAME_STR   = "test";
  private static final int       VALUE      = 42;
  private static final int       BATCH_SIZE = 10000;
  private static final Integer   PAYLOAD    = VALUE;
  private static final Integer[] PAYLOADS   = payloads ();

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex  cortex;
  private Name    name;
  private Circuit circuit;

  // Baseline: plain async pipe (no fiber)
  private Pipe < Integer > plainPipe;

  // Identity fiber (Forwarder only)
  private Pipe < Integer > identityPipe;

  // Stateless
  private Pipe < Integer > guardPipe;
  private Pipe < Integer > peekPipe;

  // Stateful
  private Pipe < Integer > diffPipe;
  private Pipe < Integer > heartbeatPipe;
  private Pipe < Integer > limitPipe;
  private Pipe < Integer > everyPipe;
  private Pipe < Integer > everyDurationPipe;
  private Pipe < Integer > chancePipe;
  private Pipe < Integer > reducePipe;

  // Composed
  private Pipe < Integer > guardDiffPipe;

  // Bound (since 2.3) — comparator-taking vs natural-ordering form
  private Pipe < Integer > aboveComparePipe;
  private Pipe < Integer > aboveNaturalPipe;

  // Distinct (since 2.5) — bounded only; the unbounded profile sweep was
  // removed for reporting errors at or above its scores, and its fixture with it.
  private Pipe < Integer > distinctBoundedPipe;

  // Route (since 2.5) — predicate never matches vs always matches
  private Pipe < Integer > routeNoMatchPipe;
  private Pipe < Integer > routeMatchPipe;

  // When (since 2.5) — predicate never matches vs always matches
  private Pipe < Integer > whenNoMatchPipe;
  private Pipe < Integer > whenMatchPipe;

  // Streak (since 2.5) — match-and-hold vs miss-and-reset vs fire-every-emission
  private Pipe < Integer > streakMatchPipe;
  private Pipe < Integer > streakMissPipe;
  private Pipe < Integer > streakFirePipe;

  // Sink for creation benchmarks
  private Pipe < Integer >          sink;
  /// Recipe assembled once at wiring time; only its attachment is measured.
  private Flow < Integer, Integer > guardFiberFlow;

  /// Precomputed changing payloads preserve stateful operator branch behavior
  /// without boxing new Integer emissions inside benchmark loops.
  private static Integer[] payloads () {

    final var payloads =
      new Integer[BATCH_SIZE];

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      payloads[i] =
        VALUE + i;
    }

    return payloads;

  }

  // =============================
  // EMISSION BENCHMARKS
  // =============================

  ///
  /// above with explicit comparator — bound-filter baseline.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void above_compare_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      aboveComparePipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// above with natural ordering (since 2.3) — should match
  /// `above_compare_batch` per emission; the only divergence is the
  /// one-shot eager probe paid at chain-build, which is off the measured
  /// path because the chain is built once at wiring time.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void above_natural_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      aboveNaturalPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Baseline: plain async pipe batch emission with await.
  /// Reference for measuring fiber operator overhead.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void baseline_plain_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      plainPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// chance — stateful probabilistic pass.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void chance_batch (
    final TerminalVerification verification
  ) {

    verification.between (
      deliveries,
      BATCH_SIZE * 2L / 5,
      BATCH_SIZE * 3L / 5
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      chancePipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// diff — stateful deduplication.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void diff_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      diffPipe.emit ( PAYLOADS[i] );
    }

    circuit.await ();

  }

  ///
  /// distinct(N) — stateful sliding-window deduplication via ring + HashSet.
  /// Window capacity set to half the batch so evictions occur mid-run.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void distinct_bounded_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      distinctBoundedPipe.emit ( PAYLOADS[i] );
    }

    circuit.await ();

  }

  ///
  /// every — stateful every-Nth pass (interval sampling).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void every_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE / 2
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      everyPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// every(Duration) — stateful processing-time interval sampling.
  /// Uses a long interval so the first emission anchors the interval and the
  /// rest of the batch is deterministically suppressed while still exercising
  /// the valve-local `nanoTime()` lookup on every processed emission.
  ///
  /// Suppression is the measured outcome here, so the expected delivery count is
  /// zero for almost every invocation: the processing-time window is a second
  /// while an invocation spans on the order of a hundred microseconds, so the
  /// window elapses during roughly one invocation in eight thousand and lets a
  /// single emission through. The `0..1` bound is therefore a weak assertion by
  /// construction — it cannot catch a disconnected pipe the way the exact counts
  /// elsewhere do — but tightening it would mean adding a stage to the operator
  /// under test, which would corrupt the cost the benchmark exists to measure.
  ///
  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void every_duration_batch (
    final TerminalVerification verification
  ) {

    verification.between (
      deliveries,
      0,
      1
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      everyDurationPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// guard — stateless predicate filter.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void guard_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      guardPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// guard + diff — representative composed chain.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void guard_diff_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      guardDiffPipe.emit ( PAYLOADS[i] );
    }

    circuit.await ();

  }

  ///
  /// heartbeat with changing values — every emission differs from the last, so
  /// the lazy heartbeat never consults the processing-time clock. Expected to
  /// match `diff_batch`; validates that a changing stream pays no clock cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void heartbeat_changing_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      heartbeatPipe.emit ( PAYLOADS[i] );
    }

    circuit.await ();

  }

  ///
  /// heartbeat with a constant value — every emission after the first is a
  /// duplicate, so each one consults the valve-local `nanos()` clock. The 1s
  /// window suppresses the batch, isolating the duplicate-path clock-read cost.
  ///
  /// Suppression is the measured outcome here, so the expected delivery count is
  /// zero for almost every invocation: the processing-time window is a second
  /// while an invocation spans on the order of a hundred microseconds, so the
  /// window elapses during roughly one invocation in eight thousand and lets a
  /// single emission through. The `0..1` bound is therefore a weak assertion by
  /// construction — it cannot catch a disconnected pipe the way the exact counts
  /// elsewhere do — but tightening it would mean adding a stage to the operator
  /// under test, which would corrupt the cost the benchmark exists to measure.
  ///
  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void heartbeat_duplicate_batch (
    final TerminalVerification verification
  ) {

    verification.between (
      deliveries,
      0,
      1
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      heartbeatPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Identity fiber — no operators, pure attachment overhead.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void identity_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      identityPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  // =============================
  // CREATION BENCHMARKS
  // =============================

  ///
  /// limit — stateful count cap (backpressure).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void limit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      limitPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// peek — stateless side-effect.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void peek_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      peekPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Pipe creation from a fiber-attached flow.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_fiber () {

    return
      guardFiberFlow.pipe ( sink );

  }

  ///
  /// reduce — stateful accumulator.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void reduce_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      reducePipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// route — predicate always matches; measures side-pipe dispatch cost.
  /// `route` drops matched values from the main pipeline, so all BATCH_SIZE
  /// deliveries arrive at the side pipe and none downstream; the side pipe is
  /// counted so the total still verifies against the emitted count.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void route_match_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      routeMatchPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// route — predicate never matches; pure pass-through cost (predicate test only).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void route_noMatch_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      routeNoMatchPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit (
        name
      );

    plainPipe =
      circuit.pipe (
        _ -> deliveries.increment ()
      );

    identityPipe = attached ( cortex.fiber ( Integer.class ) );

    guardPipe = attached (
      cortex.fiber ( Integer.class ).guard ( v -> v > 0 )
    );

    peekPipe = attached (
      cortex.fiber ( Integer.class ).peek ( _ -> {
      } )
    );

    diffPipe = attached (
      cortex.fiber ( Integer.class ).diff ()
    );

    heartbeatPipe = attached (
      cortex.fiber ( Integer.class ).heartbeat ( Duration.ofSeconds ( 1L ) )
    );

    limitPipe = attached (
      cortex.fiber ( Integer.class ).limit ( Long.MAX_VALUE )
    );

    everyPipe = attached (
      cortex.fiber ( Integer.class ).every ( 2 )
    );

    everyDurationPipe = attached (
      cortex.fiber ( Integer.class ).every ( Duration.ofSeconds ( 1L ) )
    );

    chancePipe = attached (
      cortex.fiber ( Integer.class ).chance ( 0.5 )
    );

    reducePipe = attached (
      cortex.fiber ( Integer.class ).reduce ( 0, Integer::sum )
    );

    guardDiffPipe = attached (
      cortex.fiber ( Integer.class ).guard ( v -> v > 0 ).diff ()
    );

    aboveComparePipe = attached (
      cortex.fiber ( Integer.class ).above ( Integer::compareTo, 0 )
    );

    aboveNaturalPipe = attached (
      cortex.fiber ( Integer.class ).above ( 0 )
    );

    distinctBoundedPipe = attached (
      cortex.fiber ( Integer.class ).distinct ( BATCH_SIZE / 2 )
    );

    final Pipe < Integer > routeSink =
      circuit.pipe (
        _ -> deliveries.increment ()
      );

    routeNoMatchPipe = attached (
      cortex.fiber ( Integer.class ).route ( v -> false, routeSink )
    );

    routeMatchPipe = attached (
      cortex.fiber ( Integer.class ).route ( v -> true, routeSink )
    );

    final Fiber < Integer > subFiber =
      cortex.fiber ( Integer.class ).guard ( v -> v > 0 );

    whenNoMatchPipe = attached (
      cortex.fiber ( Integer.class ).when ( v -> false, subFiber )
    );

    whenMatchPipe = attached (
      cortex.fiber ( Integer.class ).when ( v -> true, subFiber )
    );

    streakMatchPipe = attached (
      cortex.fiber ( Integer.class ).streak ( Integer.MAX_VALUE, v -> true )
    );

    streakMissPipe = attached (
      cortex.fiber ( Integer.class ).streak ( 3, v -> false )
    );

    // required=2 with always-true predicate alternates hold (count 0→1) and
    // fire (count 1→2, emit, reset). required=1 is intentionally avoided
    // because it collapses to PredicateSpec — see Fibers.java#streak.
    streakFirePipe = attached (
      cortex.fiber ( Integer.class ).streak ( 2, v -> true )
    );

    sink =
      circuit.pipe (
        Receptor.of ( Integer.class )
      );

    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    guardFiberFlow =
      cortex.flow ( Integer.class )
        .fiber ( cortex.fiber ( Integer.class ).guard ( v -> v > 0 ) );

    name =
      cortex.name (
        NAME_STR
      );

  }

  ///
  /// streak — required=2 with always-true predicate; alternates between
  /// hold (count 0→1) and fire-and-reset (count 1→2, emit, reset). Measures
  /// the steady-state increment + compare + branch + reset path.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void streak_fire_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE / 2
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      streakFirePipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// streak — predicate always matches but threshold is unreachable;
  /// measures the match → increment → hold path (no emission).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void streak_match_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      0
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      streakMatchPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// streak — predicate always misses; measures the reset-and-drop path
  /// (no counter increment, no emission).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void streak_miss_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      0
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      streakMissPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

  ///
  /// when — predicate always matches; measures sub-fiber dispatch cost on every emission.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void when_match_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      whenMatchPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// when — predicate never matches; measures pass-through cost (predicate test only,
  /// no sub-fiber dispatch).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void when_noMatch_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      whenNoMatchPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  private Pipe < Integer > attached (
    final Fiber < Integer > fiber
  ) {

    final var tally =
      deliveries;

    return
      cortex.flow ( Integer.class )
        .fiber ( fiber )
        .pipe ( circuit.pipe ( _ -> tally.increment () ) );

  }

}
