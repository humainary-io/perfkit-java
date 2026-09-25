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
/// Benchmark for `Flow.run` and `Flow.change` — value-agnostic run-length operators.
///
/// `run` maps admissions into a `Run` carrier (emission + consecutive-run length);
/// `change` emits a `Change` carrier at run boundaries (value-unequal admissions).
///
/// ## Benchmark Categories
///
/// 1. **run constant / changing**: increment path (value-equal) vs reset path (value-unequal)
/// 2. **change fires / suppresses**: changing input (forwards Change) vs constant input (suppressed)
/// 3. **Attachment**: `pipe(target)` materialization cost
///
/// Each emission benchmark drains via `circuit.await()`. Compares against
/// `RelateOps` (pairwise projection) and `ScanOps` (stateful fold).
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class RunChangeOps
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

  // run over constant input → length increments (value-equal every admission)
  private Pipe < Integer > runConstantPipe;

  // run over changing input → length resets to 1 every admission (value-unequal)
  private Pipe < Integer > runChangingPipe;

  // change over changing input → boundary every admission → forwards a Change
  private Pipe < Integer > changeFiresPipe;

  // change over constant input → one long run → no boundary, no forward
  private Pipe < Integer > changeSuppressesPipe;

  // Typed sinks for the attachment (pipe_create) benchmarks
  private Pipe < Run < Integer > >             runSink;
  private Pipe < Change < Integer > >          changeSink;
  /// Recipes assembled once at wiring time; only attachment is measured.
  private Flow < Integer, Change < Integer > > changeFlow;
  private Flow < Integer, Run < Integer > >    runFlow;


  /// Precomputed changing payloads preserve run/change branch behavior without
  /// boxing new Integer emissions inside benchmark loops.
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
  /// change over changing input: every admission is a boundary, so a Change
  /// envelope is forwarded downstream every admission after the first. Measures
  /// detection + forward.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void change_fires_batch (
    final TerminalVerification verification
  ) {

    // `change` needs a predecessor, so the very first admission of a trial has
    // no transition to report and is suppressed; every later invocation sees the
    // previous one's last value and forwards the whole batch. Hence the seam of
    // one. `relate` shows no seam because it is given an explicit initial value.
    verification.between (
      deliveries,
      BATCH_SIZE - 1,
      BATCH_SIZE
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      changeFiresPipe.emit ( PAYLOADS[i] );
    }

    circuit.await ();

  }

  ///
  /// change over constant input: a single long run with no boundary after the
  /// first admission, so nothing is forwarded. Measures detection + no-forward.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void change_suppresses_batch (
    final TerminalVerification verification
  ) {

    // Constant input: change() forwards only on a transition, so at most the
    // very first admission of an iteration survives.
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
      changeSuppressesPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Pipe attachment of a change Flow — exercises the per-attachment Receptor
  /// construction plus wrapper construction.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_change () {

    return
      changeFlow.pipe ( changeSink );

  }


  // =============================
  // CREATION BENCHMARKS
  // =============================

  ///
  /// Pipe attachment of a run Flow — exercises the per-attachment Receptor
  /// construction plus wrapper construction.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_run () {

    return
      runFlow.pipe ( runSink );

  }

  ///
  /// run over changing input: every admission differs from its predecessor, so
  /// the length resets to 1. A Run envelope is forwarded downstream every
  /// admission.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void run_changing_batch (
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
      runChangingPipe.emit ( PAYLOADS[i] );
    }

    circuit.await ();

  }

  ///
  /// run over constant input: every admission is value-equal, so the length
  /// increments. A Run envelope is forwarded downstream every admission.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void run_constant_batch (
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
      runConstantPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  // =============================
  // SETUP / TEARDOWN
  // =============================

  @Setup ( Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit (
        name
      );

    runConstantPipe = attach ( cortex.flow ( Integer.class ).run () );
    runChangingPipe = attach ( cortex.flow ( Integer.class ).run () );
    changeFiresPipe = attach ( cortex.flow ( Integer.class ).change () );
    changeSuppressesPipe = attach ( cortex.flow ( Integer.class ).change () );

    runSink = circuit.pipe ();
    changeSink = circuit.pipe ();

    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    changeFlow =
      cortex.flow ( Integer.class ).change ();

    runFlow =
      cortex.flow ( Integer.class ).run ();

    name =
      cortex.name (
        NAME_STR
      );

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

  private < O > Pipe < Integer > attach (
    final Flow < Integer, O > flow
  ) {

    return
      flow.pipe ( circuit.pipe ( _ -> deliveries.increment () ) );

  }

}
