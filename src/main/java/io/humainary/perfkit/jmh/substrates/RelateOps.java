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
/// Benchmark for `Flow.relate` — type-changing pairwise projection.
///
/// `prev` seeds from `initial`, advances on every emission, and the
/// `(prev, curr)` bi-function projects to the output (or `null` to filter).
///
/// ## Benchmark Categories
///
/// 1. **relate fires**: monotonic input, `prev != curr` always → forwards downstream
/// 2. **relate suppresses**: monotonic input, `prev == curr` never holds → filtered
/// 3. **Attachment**: `pipe(target)` materialization cost. (Wiring construction is not measured).
///
/// Compares against `ScanOps.scan_runningSum_batch` (stateful
/// type-changing fold) and `FlowOps.map_batch` (stateless baseline).
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class RelateOps
  implements Substrates {

  private static final String    NAME_STR   = "test";
  private static final int       VALUE      = 42;
  private static final int       BATCH_SIZE = 10000;
  private static final Integer[] PAYLOADS   = payloads ();

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex  cortex;
  private Name    name;
  private Circuit circuit;

  // relate that forwards on every emission (monotonic input never repeats)
  private Pipe < Integer > relateFiresPipe;

  // relate that filters on every emission (monotonic input never repeats)
  private Pipe < Integer > relateSuppressesPipe;

  // Sink for creation benchmarks
  private Pipe < Integer >          sink;
  /// Recipe assembled once at wiring time; only its attachment is measured.
  private Flow < Integer, Integer > relateFlow;


  /// Precomputed monotonic payloads preserve relate branch behavior without
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
  /// Pipe attachment of a relate Flow — exercises the per-attachment Receptor
  /// construction (prev slot seeded from initial) plus wrapper construction.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_relate () {

    return
      relateFlow.pipe ( sink );

  }

  // =============================
  // CREATION BENCHMARKS
  // =============================

  ///
  /// Relate forwards on every emission: monotonic input means `prev != curr`
  /// always holds, so `op` returns `curr` and the result is forwarded
  /// downstream. Measures detection + forward.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void relate_fires_batch (
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
      relateFiresPipe.emit ( PAYLOADS[i] );
    }

    circuit.await ();

  }

  ///
  /// Relate filters on every emission: monotonic input means `prev == curr`
  /// never holds, so `op` returns `null` and the emission is dropped.
  /// Measures detection + null-skip (no downstream forward).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void relate_suppresses_batch (
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
      relateSuppressesPipe.emit ( PAYLOADS[i] );
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

    relateFiresPipe = attach (
      cortex.flow ( Integer.class )
        .relate ( 0, ( prev, curr ) -> prev.equals ( curr ) ? null : curr )
    );

    relateSuppressesPipe = attach (
      cortex.flow ( Integer.class )
        .relate ( 0, ( prev, curr ) -> prev.equals ( curr ) ? curr : null )
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

    relateFlow =
      cortex.flow ( Integer.class )
        .relate ( 0, ( prev, curr ) -> prev.equals ( curr ) ? null : curr );

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
