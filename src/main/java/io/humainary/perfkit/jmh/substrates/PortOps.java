// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Port operations.
///
/// Measures the four Port operations — `replace`, `update(fn)`,
/// `update(arg, fn)`, and `emit(pipe)` — at the public endpoint. Every
/// benchmark awaits, so a score is the queued operation plus the port's
/// worker-thread processing.
///
/// Pre-allocated reference tokens are used as payloads to avoid allocation noise.
///
/// ## Completion Evidence
///
/// `emit(pipe)` publishes to a counted pipe, and the two `update` forms count
/// inside the transformation the worker applies, so all three verify an exact
/// count of `BATCH_SIZE` against [Tally].
///
/// `replace` is write-only by capability design (no read operation), so its completion
/// evidence is the in-body drain alone.
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class PortOps
  implements Substrates {

  private static final String  NAME_STR   = "port";
  private static final int     BATCH_SIZE = 10000;
  private static final Value   INITIAL    = new Value ( -1 );
  private static final Value[] VALUES     = values ();

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex  cortex;
  private Name    name;
  private Circuit circuit;

  private Port < Value > port;
  private Pipe < Value > sinkPipe;
  private Pipe < Value > emptyPipe;

  /// Transformations held in fields rather than built per call: each is
  /// allocated once at iteration setup, so the measured loop pays the submit
  /// cost and nothing else, and each counts the application the worker performs.
  private UnaryOperator < Value >            identity;
  private BiFunction < Value, Value, Value > pickArg;

  //
  // PREALLOCATED PAYLOADS
  //

  private static Value[] values () {

    final var values =
      new Value[BATCH_SIZE];

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      values[i] =
        new Value (
          i
        );
    }

    return values;

  }

  //
  // BASELINE
  //

  ///
  /// Empty pipe batch with await. Comparison baseline for the Port batches:
  /// one admission per operation delivered to a counting receptor, with no
  /// port involved.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void baseline_empty_pipe_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      emptyPipe.emit (
        VALUES[i]
      );
    }

    circuit.await ();

  }

  //
  // PUBLICATION
  //

  ///
  /// `emit(pipe)` batch with await. Publishes the port's current value to a
  /// counted pipe; the score covers the queued port operation plus the
  /// downstream pipe dispatch, so it exceeds the baseline by the port hop.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      port.emit (
        sinkPipe
      );
    }

    circuit.await ();

  }

  //
  // UPDATES
  //

  ///
  /// `replace` batch with await. Measures the cost of the queued whole-value
  /// replacement plus the worker-side store.
  ///
  /// This is the one row in the class with no terminal observable — see the
  /// class documentation. Its drain proves the queue emptied and nothing more.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void replace_batch () {

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      port.replace (
        VALUES[i]
      );
    }

    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    circuit =
      cortex.circuit (
        name
      );

    port =
      circuit.port (
        INITIAL
      );

    deliveries.take ();

    sinkPipe =
      circuit.pipe ( _ -> deliveries.increment () );

    emptyPipe =
      circuit.pipe ( _ -> deliveries.increment () );

    identity =
      current -> {
        deliveries.increment ();
        return current;
      };

    pickArg =
      ( _, arg ) -> {
        deliveries.increment ();
        return arg;
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

  //
  // FIXTURES
  //

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

  ///
  /// `update(arg, fn)` batch with await. Measures the deterministic
  /// arg-passing allocation: one `UpdateArg` holder per call, no capturing
  /// lambda, with the worker-side application counted.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void update_arg_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      port.update (
        VALUES[i],
        pickArg
      );
    }

    circuit.await ();

  }

  ///
  /// `update(fn)` batch with await. The transformation is a field-held
  /// non-capturing operator, so allocation is bounded to the per-call submit
  /// overhead, and its application is what the verification counts.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void update_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE
    );

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      port.update (
        identity
      );
    }

    circuit.await ();

  }

  record Value(
    int value
  ) {
  }

}
