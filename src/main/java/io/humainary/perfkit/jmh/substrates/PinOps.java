// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Pin operations.
///
/// Pin `get` and `set` operations are immediate (not queued), so the measurement
/// strategy differs from Cell/Port. Each invocation runs inside a circuit pipe
/// receptor where the guard passes, executing tight read/write loops. The await
/// cost is amortized once per batch invocation.
///
/// Compares Pin's guard cost against a plain circuit-local field baseline
/// (`baseline_field_get_loop` / `baseline_field_set_loop`).
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class PinOps
  implements Substrates {

  private static final String  NAME_STR    = "pin";
  private static final int     LOOP_SIZE   = 10000;
  private static final Value   INITIAL     = new Value ( -1 );
  private static final Value   REPLACEMENT = new Value ( 42 );
  /// Holder for the baseline benchmarks: a plain field accessed without any
  /// guard, modelling the no-overhead lower bound for circuit-local mutable
  /// state.
  private final        Value[] baseline    = {INITIAL};
  /// Receptor invocations counted by the circuit context, held off this state object.
  private final        Tally   deliveries  = new Tally ();

  private Cortex          cortex;
  private Name            name;
  private Circuit         circuit;
  private Pin < Value >   pin;
  private Pipe < Object > getLoopPipe;
  private Pipe < Object > setLoopPipe;
  private Pipe < Object > getSetLoopPipe;
  private Pipe < Object > baselineGetLoopPipe;
  private Pipe < Object > baselineSetLoopPipe;
  /// Blackhole consumer used inside the receptor loops to prevent the JIT
  /// from eliding the read.
  private Blackhole       sink;

  //
  // FACTORY
  //

  ///
  /// Baseline for `get_loop_owner_context`: identical receptor structure and
  /// dispatch cost, but reads a plain field with no guard. Difference from
  /// the guarded variant is the upper bound on Pin's per-access overhead.
  ///

  @Benchmark
  @OperationsPerInvocation ( LOOP_SIZE )
  public void baseline_field_get_loop (
    final TerminalVerification verification
  ) {

    // One admission per invocation; the measured loop runs inside the
    // receptor, so the delivery count is one rather than LOOP_SIZE.
    verification.expect (
      deliveries,
      1
    );

    baselineGetLoopPipe.emit ( this );
    circuit.await ();

  }

  ///
  /// Baseline for `set_loop_owner_context`: plain field write with no guard.
  ///

  @Benchmark
  @OperationsPerInvocation ( LOOP_SIZE )
  public void baseline_field_set_loop (
    final TerminalVerification verification
  ) {

    // One admission per invocation; the measured loop runs inside the
    // receptor, so the delivery count is one rather than LOOP_SIZE.
    verification.expect (
      deliveries,
      1
    );

    baselineSetLoopPipe.emit ( this );
    circuit.await ();

  }

  //
  // GUARDED ACCESS — owner context
  //

  //
  // BASELINE — same loop shape against a plain field
  //

  ///
  /// `pin.get()` in a tight loop on the owner context. Measures the guard
  /// cost (currentThread() + ref compare) plus the field read, amortized
  /// across a single circuit dispatch + await.
  ///

  @Benchmark
  @OperationsPerInvocation ( LOOP_SIZE )
  public void get_loop_owner_context (
    final TerminalVerification verification
  ) {

    // One admission per invocation; the measured loop runs inside the
    // receptor, so the delivery count is one rather than LOOP_SIZE.
    verification.expect (
      deliveries,
      1
    );

    getLoopPipe.emit ( this );
    circuit.await ();

  }

  ///
  /// Alternating `set` then `get` in a tight loop. Measures the cost of a
  /// realistic "mutate then read back" pattern that Pin exists to support.
  ///

  @Benchmark
  @OperationsPerInvocation ( LOOP_SIZE )
  public void get_set_loop_owner_context (
    final TerminalVerification verification
  ) {

    // One admission per invocation; the measured loop runs inside the
    // receptor, so the delivery count is one rather than LOOP_SIZE.
    verification.expect (
      deliveries,
      1
    );

    getSetLoopPipe.emit ( this );
    circuit.await ();

  }

  ///
  /// `pin.set(value)` in a tight loop on the owner context. Measures the
  /// guard cost plus the field write.
  ///

  @Benchmark
  @OperationsPerInvocation ( LOOP_SIZE )
  public void set_loop_owner_context (
    final TerminalVerification verification
  ) {

    // One admission per invocation; the measured loop runs inside the
    // receptor, so the delivery count is one rather than LOOP_SIZE.
    verification.expect (
      deliveries,
      1
    );

    setLoopPipe.emit ( this );
    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    circuit =
      cortex.circuit (
        name
      );

    pin =
      circuit.pin (
        INITIAL
      );

    // The receptor closures run on the owner worker thread, so the guard
    // passes and the loops execute on the hot path.

    getLoopPipe =
      circuit.pipe ( ( Object ignored ) -> {
        deliveries.increment ();
        final Blackhole bh = sink;
        for ( var i = 0; i < LOOP_SIZE; i++ ) {
          bh.consume ( pin.get () );
        }
      } );

    setLoopPipe =
      circuit.pipe ( ( Object ignored ) -> {
        deliveries.increment ();
        for ( var i = 0; i < LOOP_SIZE; i++ ) {
          pin.set ( REPLACEMENT );
        }
      } );

    getSetLoopPipe =
      circuit.pipe ( ( Object ignored ) -> {
        deliveries.increment ();
        final Blackhole bh = sink;
        for ( var i = 0; i < LOOP_SIZE; i++ ) {
          pin.set ( REPLACEMENT );
          bh.consume ( pin.get () );
        }
      } );

    baselineGetLoopPipe =
      circuit.pipe ( ( Object ignored ) -> {
        deliveries.increment ();
        final Blackhole bh = sink;
        for ( var i = 0; i < LOOP_SIZE; i++ ) {
          bh.consume ( baseline[0] );
        }
      } );

    baselineSetLoopPipe =
      circuit.pipe ( ( Object ignored ) -> {
        deliveries.increment ();
        for ( var i = 0; i < LOOP_SIZE; i++ ) {
          baseline[0] = REPLACEMENT;
        }
      } );

    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial ( final Blackhole bh ) {

    cortex =
      Substrates.cortex ();

    name =
      cortex.name (
        NAME_STR
      );

    sink = bh;

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

  record Value(
    int value
  ) {
  }

}
