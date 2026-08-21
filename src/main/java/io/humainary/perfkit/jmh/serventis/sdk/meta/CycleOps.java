// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.serventis.sdk.meta;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.serventis.opt.pool.Resources;
import io.humainary.serventis.sdk.meta.Cycles;
import io.humainary.serventis.sdk.meta.Cycles.Cycle;
import io.humainary.serventis.sdk.meta.Cycles.Signal;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static io.humainary.serventis.opt.pool.Resources.Sign.GRANT;
import static io.humainary.serventis.sdk.meta.Cycles.Dimension.*;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Cycles.Cycle operations.
///
/// Measures the performance of cycle signal emissions across dimensions
/// (SINGLE, REPEAT, RETURN) using generic Sign types.
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class CycleOps implements Substrates {

  private static final String CYCLE_NAME = "resource.cycles";
  private static final String FLOW_NAME  = "resource.cycles.flow";
  private static final int    BATCH_SIZE = 10000;

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex                            cortex;
  private Circuit                           circuit;
  private Pool < Cycle < Resources.Sign > > pool;
  private Cycle < Resources.Sign >          cycle;
  private Pipe < Resources.Sign >           inputFlow;
  private Name                              name;
  private Name                              flowName;

  ///
  /// Benchmark the canonical detector flow, drained — `Cycles.flow` classifying SINGLE/REPEAT per
  /// admission over a repeating sign stream, with `circuit.await()` for the true per-sign cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_flow_batch (
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
      inputFlow.emit (
        GRANT
      );
    }

    circuit.await ();

  }

  ///
  /// Benchmark batched REPEAT emissions, drained with `circuit.await()` — end-to-end per-emission cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_repeat_batch (
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
      cycle.signal (
        GRANT,
        REPEAT
      );
    }

    circuit.await ();

  }

  ///
  /// Benchmark batched RETURN emissions, drained with `circuit.await()` — end-to-end per-emission cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_return_batch (
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
      cycle.signal (
        GRANT,
        RETURN
      );
    }

    circuit.await ();

  }

  ///
  /// Benchmark batched generic signal emissions, drained with `circuit.await()` — end-to-end cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_signal_batch (
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
      cycle.signal (
        GRANT,
        SINGLE
      );
    }

    circuit.await ();

  }

  ///
  /// Benchmark batched SINGLE emissions, drained with `circuit.await()` — end-to-end per-emission cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_single_batch (
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
      cycle.signal (
        GRANT,
        SINGLE
      );
    }

    circuit.await ();

  }

  @Setup ( Level.Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit ();

    @SuppressWarnings ( "unchecked" ) final var c =
      (Conduit < Signal < Resources.Sign > >) (Conduit < ? >)
        circuit.conduit ( Cycles.Signal.class );

    c.subscribe (
      circuit.subscriber (
        cortex.name ( "observer" ),
        ( _, registrar ) ->
          registrar.register ( _ -> deliveries.increment () )
      )
    );

    pool =
      Cycles.pool ( Resources.SIGNS, c );

    cycle =
      pool.get (
        name
      );

    inputFlow =
      c.pool ( Cycles.flow ( Resources.SIGNS ) )
        .get ( flowName );

    circuit.await ();

  }

  @Setup ( Level.Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    name =
      cortex.name (
        CYCLE_NAME
      );

    flowName =
      cortex.name (
        FLOW_NAME
      );

  }

  @TearDown ( Level.Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

}
