// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import java.util.List;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for emissions that cross a circuit boundary.
///
/// `PipeOps.pipe_target_same_circuit` measures the comparison that makes
/// same-circuit chaining free — `circuit.pipe(Pipe)` returns a target that
/// already belongs to this circuit unchanged. This class measures the other
/// branch, which `PipeOps` names and does not take: an emission admitted to one
/// circuit, run as work there, and admitted onward to the circuit that owns the
/// target.
///
/// ## What a row costs
///
/// Two admissions and two workers per emission, against one of each for a
/// same-circuit pipe. **`PipeOps.async_emit_batch` is the reference**, not a row
/// here: subtracting it from `cross_circuit_pipe_emit_batch` gives the price of
/// the boundary. The rows are not repeated on this class, because a second
/// measurement of the same topology would only drift from the first.
///
/// ## Why the target's kind gets its own row
///
/// A cross-circuit target is reached by admitting to its circuit, never by
/// calling into it, and the two rows here differ in what the target circuit then
/// does with the item. A **plain pipe** runs a receptor. A **channel** runs its
/// registrations, which is more work on that side and none on this one.
///
/// The distinction is here because a defect lived in it: the adapter used to
/// bind the target as its own receptor, which a plain pipe absorbed — its
/// `receive` re-submits to its own valve — while a channel dispatched its
/// registrations on the emitting circuit's worker. Both rows now measure the
/// same mechanism on this side of the boundary.
/// `CrossCircuitContractTest` is what holds the behaviour; this is what prices
/// it.
///
/// ## Read the rows together
///
/// The rows sit about 1 ns apart for the two single-target shapes, the channel
/// above the plain pipe, which is the order their targets' work argues for.
/// They have not always measured that way: a session once put the channel 8 ns
/// *below* the plain pipe, consistently enough across three decision runs to be
/// written up as a finding, and the same provider re-measured the next day gave
/// the ordinary order. `BENCHMARKS.md` records the withdrawal. Nothing about the
/// boundary was learned from it, and one thing about measuring was — agreement
/// within a session is one reading, not several.
///
/// So judge a change to the boundary on all four rows moving together, and on a
/// before and after taken far enough apart to have met different machine states.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class CrossCircuitOps
  implements Substrates {

  private static final int     BATCH_SIZE = 10000;
  // Pre-boxed: the autobox cache stops at 127, so emitting `VALUE + i` would
  // allocate per emission and price boxing rather than the boundary.
  private static final Integer PAYLOAD    = 42;

  /// Written only by the owning circuit's context, read after both drains.
  private final Tally            deliveries = new Tally ();
  private       Cortex           cortex;
  /// The circuit an emission is admitted to.
  private       Circuit          entry;
  /// The circuit that owns every target below, and the only context that counts.
  private       Circuit          owner;
  /// `entry.pipe(ownerChannel)` — the adapter onto a conduit channel.
  private       Pipe < Integer > toChannel;
  /// `entry.pipe(List.of(ownerPipe, ownerPipe))` — the fan-out branch, which
  /// resolves its targets through the same admission but reaches it by a
  /// different path.
  private       Pipe < Integer > toFanout;
  /// `entry.pipe(ownerPipe)` — the adapter onto a plain circuit pipe.
  private       Pipe < Integer > toPipe;

  ///
  /// Batch adapter emissions to a channel on the owning circuit, with both
  /// circuits drained.
  ///
  /// The target circuit runs the channel's registration rather than a receptor,
  /// so this carries the dispatch a channel owes on top of the boundary itself.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void cross_circuit_channel_emit_batch (
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
      toChannel.emit ( PAYLOAD );
    }

    entry.await ();
    owner.await ();

  }

  ///
  /// Batch caller-side admissions into the entry circuit. Both circuits are
  /// drained in invocation teardown, outside the primary timer.
  ///
  /// The control for every row here. The caller reaches the entry circuit's
  /// queue without consulting the target at all, so no per-target cost is
  /// visible from this side — but this is **not** the same measurement as
  /// `PipeOps.async_emit_admission_batch`, and measured about 1.7 ns above it.
  /// The entry circuit's worker is doing more than counting here: it is draining
  /// and admitting onward, and it takes the same ingress guard the caller does.
  /// What this row isolates is the caller's side of a boundary that is busy,
  /// which is what a caller actually meets.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void cross_circuit_emit_admission_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE,
      entry,
      owner
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      toPipe.emit ( PAYLOAD );
    }

  }

  ///
  /// Batch fan-out emissions to two targets on the owning circuit, with both
  /// circuits drained. Normalized per source admission, not per delivery.
  ///
  /// One admission here becomes two on the far side, so this is not a control
  /// for [#cross_circuit_pipe_emit_batch] — it prices the second target. What it
  /// does establish is that a list of targets and a single adapted target reach
  /// the boundary the same way, which the provider has not always done.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void cross_circuit_fanout_emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      2L * BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      toFanout.emit ( PAYLOAD );
    }

    entry.await ();
    owner.await ();

  }

  ///
  /// Batch adapter emissions to a plain pipe on the owning circuit, with both
  /// circuits drained. Normalized per source admission.
  ///
  /// The boundary at its cheapest: the owning circuit runs one receptor. Read
  /// against `PipeOps.async_emit_batch` for what the crossing costs.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void cross_circuit_pipe_emit_batch (
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
      toPipe.emit ( PAYLOAD );
    }

    entry.await ();
    owner.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    deliveries.take ();

    entry =
      cortex.circuit (
        cortex.name ( "entry" )
      );

    owner =
      cortex.circuit (
        cortex.name ( "owner" )
      );

    toPipe =
      entry.pipe (
        owner.pipe (
          countedReceptor ()
        )
      );

    toFanout =
      entry.pipe (
        List.of (
          owner.pipe ( countedReceptor () ),
          owner.pipe ( countedReceptor () )
        )
      );

    final var conduit =
      owner.conduit (
        Integer.class
      );

    conduit.subscribe (
      owner.subscriber (
        cortex.name ( "observer" ),
        ( _, registrar ) ->
          registrar.register ( countedReceptor () )
      )
    );

    final var channel =
      conduit.get (
        cortex.name ( "channel" )
      );

    toChannel =
      entry.pipe (
        channel
      );

    // The channel's registration is discovered on its first emission, so a row
    // that paid for that discovery would price wiring rather than crossing. The
    // discovery is driven from the owning circuit rather than through the
    // adapter, so no row's dispatch site is given a profile from another row's
    // target: every emission this method makes would otherwise be delivered
    // through the same call site the measured loop uses.
    channel.emit ( PAYLOAD );

    owner.await ();

    deliveries.take ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    entry.close ();
    owner.close ();

  }

  private Receptor < Integer > countedReceptor () {

    final var tally =
      deliveries;

    return
      _ -> tally.increment ();

  }

}
