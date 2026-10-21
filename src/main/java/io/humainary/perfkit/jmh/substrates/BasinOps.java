// Copyright (c) 2026 William David Louth

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
/// Benchmark for `Substrates.Basin` operations.
///
/// Measures the basin write hot path — emitting through `basin.pipe()` into the
/// bounded ring buffer on the circuit worker — and the cost of draining the
/// buffer to a target pipe. Emit benchmarks `await` so worker-side buffering
/// cost is included, not just the caller-side enqueue.
///
/// Every batch is sized at [#BATCH_SIZE] so the one drain per invocation is a
/// small fraction of each operation. The former 100-operation scale points were
/// removed: one `await()` costs the time of hundreds of emissions, so spread
/// over 100 operations it outweighed the emission several times over and the
/// rows reported the drain rather than the basin.
///
/// Every fixture is built in [#setupIteration()]. Building them inside the
/// measured body cost about 4 B/op — an `Object[BATCH_SIZE]` ring divided by the
/// batch — but the larger problem was what a per-invocation ring measured: a
/// basin created at [#BATCH_SIZE] capacity and then given exactly [#BATCH_SIZE]
/// emissions fills once and never wraps, so the eviction path of a *bounded*
/// buffer went unmeasured and the write read as free against a plain conduit
/// emission.
///
/// The two basins are therefore sized for what their benchmarks measure:
///
/// - [#basin_emit_batch()] uses a [#RING_SIZE]-slot basin, so the ring wraps
///   about ten times per invocation and every emission after the first wrap
///   evicts. That is the steady state of a long-lived basin. Its first
///   invocation of an iteration fills a cold ring; every later one is steady
///   state, which is what the score reflects.
/// - [#basin_burst_then_drain_batch(TerminalVerification)] keeps a [#BATCH_SIZE]-slot basin so its
///   `drain` moves the whole batch rather than one ring's worth. `drain` evicts
///   what it forwards, so the basin returns empty for the next invocation
///   without a reset.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class BasinOps
  implements Substrates {

  private static final String  NAME_STR   = "test";
  private static final int     VALUE      = 42;
  private static final int     BATCH_SIZE = 10000;
  private static final Integer PAYLOAD    = VALUE;

  /// Capacity of the evicting basin. Well under [#BATCH_SIZE] so the ring wraps
  /// repeatedly within one invocation, putting the measurement on the
  /// append-and-evict path rather than on a fill that never reaches capacity.

  private static final int RING_SIZE = 1024;

  /// Drained values counted by the circuit context, held off this state object.
  private final Tally drained = new Tally ();

  /// Baseline deliveries, counted the same way.
  private final Tally delivered = new Tally ();

  private Cortex  cortex;
  private Circuit circuit;
  private Name    name;

  private Pipe < Object >  baseline;    // plain circuit pipe to a counting receptor, no basin
  private Pipe < Object >  evicting;    // feeds a RING_SIZE basin
  private Runnable         retained;    // drains that basin to target after the timer
  private Basin < Object > burstBasin;  // BATCH_SIZE capacity, drained per invocation
  private Pipe < Object >  bursting;    // feeds burstBasin
  private Pipe < Object >  target;      // counting drain target

  ///
  /// BASELINE: 10000 emissions through a circuit pipe to a receptor that counts
  /// them — the same admission and one delivery per value that a basin's pipe
  /// performs, without the buffer. A conduit channel with no subscriber would
  /// have been cheaper than either: a provider may decline to admit to it at all.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void baseline_emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      delivered,
      BATCH_SIZE
    );

    for ( int i = 0; i < BATCH_SIZE; i++ ) {
      baseline.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// DRAIN: 10000 emissions buffered, then drained to a counting target pipe.
  /// Awaits twice, which at this batch size is a small fraction of each
  /// operation. `drain` here is the measured Basin operation, not a timing
  /// boundary.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void basin_burst_then_drain_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      drained,
      BATCH_SIZE
    );

    for ( int i = 0; i < BATCH_SIZE; i++ ) {
      bursting.emit ( PAYLOAD );
    }

    circuit.await ();

    burstBasin.drain ( target );

    circuit.await ();

  }

  ///
  /// HOT PATH: 10000 emissions through a basin's pipe (write + evict cost).
  /// The basin holds [#RING_SIZE] values, so the ring wraps about ten times per
  /// invocation and all but the first [#RING_SIZE] emissions evict an older
  /// value as they append. Read against [#baseline_emit_batch(TerminalVerification)], the difference
  /// is what a bounded buffer costs over a plain delivered emission.
  ///
  /// Nothing is delivered while the timer runs, so completion is checked after
  /// it: the basin is drained and must hand over a full ring. A basin that
  /// dropped its writes would otherwise finish faster and pass.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void basin_emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      drained,
      RING_SIZE,
      retained
    );

    for ( int i = 0; i < BATCH_SIZE; i++ ) {
      evicting.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    final var deliveredTally =
      delivered;

    final var drainedTally =
      drained;

    circuit =
      cortex.circuit (
        name
      );

    delivered.take ();

    baseline =
      circuit.pipe ( _ -> deliveredTally.increment () );

    final var evictingBasin =
      circuit.basin (
        RING_SIZE
      );

    evicting =
      evictingBasin.pipe ();

    burstBasin =
      circuit.basin (
        BATCH_SIZE
      );

    bursting =
      burstBasin.pipe ();

    drained.take ();

    final var drainTarget =
      circuit.pipe ( _ -> drainedTally.increment () );

    target =
      drainTarget;

    final var owner =
      circuit;

    retained =
      () -> {
        evictingBasin.drain ( drainTarget );
        owner.await ();
      };

    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    name =
      cortex.name (
        NAME_STR
      );

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

}
