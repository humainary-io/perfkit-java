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
/// Emission through a named conduit channel, with and without an observer.
///
/// The pair exists to price a channel **nobody is listening to**, which is the
/// shape instrumentation actually runs in: an instrument is always constructed
/// and usually unobserved. No other class here covers it. `PipeOps` measures a
/// circuit pipe, whose receptor is fixed at construction and always present;
/// only a conduit channel can have none, because its routes come from
/// subscriptions that may never arrive.
///
/// The two arms are a difference, not two scores:
///
///  - **observed** is the reference — one subscription, one registered receptor.
///  - **unobserved** is the same channel with nothing subscribed to its source,
///    so no emission through it can reach anywhere. Only the wiring differs; the
///    emit loops are identical.
///
/// What the difference says is a property of the provider under measurement,
/// not of this suite. A provider may admit every emission and let the worker
/// discover it has nowhere to deliver it, in which case the arms converge; or it
/// may decline the admission when nothing is subscribed, in which case the
/// unobserved arm collapses toward the caller's own loop. Both are conforming.
/// Read the separation from a decision run against the provider in hand.
///
/// The second shape has a correctness condition worth knowing before reading a
/// suspiciously good unobserved score: the decision has to come from the
/// subscribe/close **admission** sequence, never from whatever roster the
/// worker has already processed. A roster is only reached after the
/// registration is processed, so an emitter consulting one answers for a
/// position its own emission has not arrived at, and drops the
/// `subscribe(); emit(X); await()` that SPEC §7.6.1 pins as delivered. The TCK
/// is what establishes that; these rows only price it.
///
/// **Overlap, stated rather than discovered later**: the observed rows are not
/// new coverage. `StemOps.baseline_emit_batch` at depth 1 and
/// `MirrorOps.baseline_emit_batch` are the same wiring — a conduit channel, one
/// subscriber registering a counting receptor, one drain. They are kept here as
/// this class's own control, because a cross-class reading would compare
/// separate forks, JVMs and fixtures. The unobserved rows are what no other
/// class covers.
///
/// Admission and drain are separate rows for the reason `PipeOps` separates
/// them: anything caller-side is diluted by the worker's share in an overlapped
/// row. `*_admission_batch` leaves the drain to invocation teardown and is the
/// sensitive row; `*_emit_batch` reports the complete path.
///
/// On a provider where the arms are close, the difference is caller-side and
/// small against the score carrying it, so it wants forks rather than
/// iterations: a low fork count here has both failed to resolve a real
/// difference and inverted its own sign between runs of an unchanged provider.
///
/// A second pair sits alongside, differing only in **what a subscriber
/// registers**: `observed_*` registers a receptor, `piped_*` registers a circuit
/// pipe wrapping that same receptor. SPEC §6.3 gives the two the same
/// guarantees and explicitly lets an implementation store a receptor directly
/// rather than wrap it, so what the difference costs is a provider's to choose
/// and this pair's to price.
///
/// The two pairs are sensitive on opposite sides, which is why they need
/// separate rows rather than one grid:
///
///  - **observed vs unobserved** differs on the **caller's** side — whether the
///    emission is admitted at all — so its admission rows carry the signal and
///    its complete-path rows dilute it.
///  - **observed vs piped** differs on the **worker's** side — how many times a
///    value is handed between queue and callback after admission — so its
///    complete-path rows carry the signal and its admission rows are a control
///    that should resolve to nothing.
///
/// Reading either pair's signal from the other's row is the mistake these notes
/// exist to prevent.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class ChannelOps
  implements Substrates {

  private static final int     BATCH_SIZE = 10000;
  private static final int     VALUE      = 42;
  // Pre-boxed: the autobox cache stops at 127, so emitting a shared reference is
  // what keeps the loops allocation-free. See PipeOps for the same reasoning.
  private static final Integer PAYLOAD    = VALUE;

  /// Terminal deliveries counted on the circuit context, held off this state
  /// object. Only the observed arm ever increments it.
  private final Tally                deliveries = new Tally ();
  private       Cortex               cortex;
  private       Circuit              circuit;
  private       Name                 name;
  private       Receptor < Integer > counter;
  private       Pipe < Integer >     observed;
  private       Pipe < Integer >     piped;
  private       Pipe < Integer >     composed;
  private       Pipe < Integer >     unobserved;

  ///
  /// Caller-side admissions on a channel with one registered receptor. The
  /// drain and the delivery check happen in invocation teardown, outside the
  /// timer.
  ///
  /// The reference the unobserved arm is read against, and the sensitive row of
  /// the pair: caller-side work is undiluted here. Duplicates the wiring of
  /// `StemOps.baseline_emit_batch` and `MirrorOps.baseline_emit_batch` by
  /// construction — see the class note on overlap.
  ///
  /// The row of the four most prone to per-fork compilation variance, so give it
  /// forks rather than iterations: a low fork count has returned scores here that
  /// differ by more than the effect sizes this pair exists to resolve, on
  /// providers that did not change between runs.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void observed_emit_admission_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE,
      circuit
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      observed.emit ( PAYLOAD );
    }

  }

  ///
  /// Admissions on a channel with one registered receptor, followed by one
  /// complete drain. Normalized per admission, not per terminal delivery.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void observed_emit_batch (
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
      observed.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Caller-side admissions on a channel whose subscriber registered a **pipe**.
  ///
  /// The control of the registration pair. A registration kind is a property of
  /// the route list the worker walks, and the caller reaches the queue without
  /// consulting that list, so this row and [#observed_emit_admission_batch]
  /// should agree. A difference here is a finding about the emit path, not about
  /// registration, and means the pair below is measuring something else as well.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void piped_emit_admission_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE,
      circuit
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      piped.emit ( PAYLOAD );
    }

  }

  ///
  /// The same channel and drain as [#observed_emit_batch], differing only in
  /// whether the subscriber registered a pipe or the receptor that pipe wraps.
  ///
  /// The sensitive row of the registration pair, and sensitive on the **worker's**
  /// side rather than the caller's — the opposite of the observed/unobserved
  /// pair, whose difference is entirely caller-side. Both registration kinds
  /// admit once from the caller; what can differ is how many times the value is
  /// handed between queue and callback before it arrives.
  ///
  /// A provider may treat the two identically. It may also deliver to a
  /// registered receptor without a further queue step while a registered pipe
  /// takes one, which is conforming — SPEC §6.3 grants receptor registrations
  /// the same visibility, temporal validity, confinement and failure isolation
  /// as pipe registrations, and explicitly permits storing a receptor directly
  /// rather than wrapping it in a pipe. This row is what prices that choice.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void piped_emit_batch (
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
      piped.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// A channel whose subscriber registers a **composed** pipe — a fiber
  /// materialized against a circuit pipe — rather than the circuit pipe itself.
  ///
  /// Read against [#piped_emit_batch], whose registration is the same circuit
  /// pipe with nothing in front of it. The difference is one operator plus
  /// whatever the composition costs in dispatch, and the two are not separable
  /// from this row alone: what it is for is the same row across two revisions,
  /// where the operator is constant and only the dispatch can move.
  ///
  /// A provider may treat a composed pipe exactly as it treats the pipe it wraps,
  /// or may reach it through an extra queue step because it cannot see what the
  /// composition ends at. Both are conforming.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void composed_emit_batch (
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
      composed.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    circuit =
      cortex.circuit ();

    final var observedConduit =
      circuit.conduit (
        cortex.name ( "observed" ),
        Integer.class
      );

    observedConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "observer" ),
        ( _, registrar ) ->
          registrar.register ( counter )
      )
    );

    observed =
      observedConduit.get ( name );

    // The same subscription, registering a pipe around the same receptor rather
    // than the receptor itself. Everything downstream of the registration is
    // identical, so the pair isolates what the registration kind costs.
    final var pipedConduit =
      circuit.conduit (
        cortex.name ( "piped" ),
        Integer.class
      );

    pipedConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "observer" ),
        ( _, registrar ) ->
          registrar.register (
            circuit.pipe ( counter )
          )
      )
    );

    piped =
      pipedConduit.get ( name );

    // The same registration again, with a fiber between the registered pipe and
    // the receptor. What is registered is now a composed pipe rather than a
    // terminal one, which is the only difference from the arm above.
    final var composedConduit =
      circuit.conduit (
        cortex.name ( "composed" ),
        Integer.class
      );

    composedConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "observer" ),
        ( _, registrar ) ->
          registrar.register (
            cortex
              .fiber ( Integer.class )
              .guard ( _ -> true )
              .pipe ( circuit.pipe ( counter ) )
          )
      )
    );

    composed =
      composedConduit.get ( name );

    // Never subscribed, and that is the whole configuration: the channel is
    // constructed and emitted through exactly as the observed one is.
    unobserved =
      circuit
        .conduit (
          cortex.name ( "unobserved" ),
          Integer.class
        )
        .get ( name );

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    name =
      cortex.name ( "channel" );

    counter =
      _ -> deliveries.increment ();

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

  ///
  /// Caller-side admissions on a channel with nothing subscribed to its source.
  ///
  /// Teardown drains the circuit and asserts that nothing arrived. The check is
  /// the row's control: a zero here has to mean nothing was delivered, not that
  /// the benchmark wired itself to a channel it never emitted through.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void unobserved_emit_admission_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      0L,
      circuit
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      unobserved.emit ( PAYLOAD );
    }

  }

  ///
  /// The unobserved channel's complete path, drain included. What it contains
  /// depends on the provider: a traversal per emission that ends in no delivery,
  /// or nothing at all beyond the caller's loop if the admission was declined.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void unobserved_emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      0L
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      unobserved.emit ( PAYLOAD );
    }

    circuit.await ();

  }

}
