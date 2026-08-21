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
/// Every batch is sized at [#BATCH_SIZE] so the one drain per invocation costs
/// under a nanosecond per operation. The former 100-operation scale points were
/// removed: a single `await()` is roughly 8 µs, so spreading it over 100
/// operations put ~80 ns of rendezvous on a ~11 ns emission and the rows
/// reported the drain rather than the basin.
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

  private Cortex  cortex;
  private Circuit circuit;
  private Name    name;

  private Pipe < Object >  baseline;    // plain conduit pipe, no basin
  private Pipe < Object >  evicting;    // feeds a RING_SIZE basin
  private Basin < Object > burstBasin;  // BATCH_SIZE capacity, drained per invocation
  private Pipe < Object >  bursting;    // feeds burstBasin
  private Pipe < Object >  target;      // black-hole drain target

  ///
  /// BASELINE: 10000 emissions into a conduit pipe (pure emission cost).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void baseline_emit_batch () {

    for ( int i = 0; i < BATCH_SIZE; i++ ) {
      baseline.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// DRAIN: 10000 emissions buffered, then drained to a no-op target pipe.
  /// Awaits twice, which at this batch size is under two nanoseconds per
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
  /// value as they append. Read against [#baseline_emit_batch()], the difference
  /// is what a bounded buffer costs over a plain conduit emission.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void basin_emit_batch () {

    for ( int i = 0; i < BATCH_SIZE; i++ ) {
      evicting.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    circuit =
      cortex.circuit (
        name
      );

    baseline =
      circuit.conduit ().get (
        name
      );

    evicting =
      circuit.basin (
        RING_SIZE
      ).pipe ();

    burstBasin =
      circuit.basin (
        BATCH_SIZE
      );

    bursting =
      burstBasin.pipe ();

    drained.take ();

    target =
      circuit.pipe ( _ -> drained.increment () );

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
