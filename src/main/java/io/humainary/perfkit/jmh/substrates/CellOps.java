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
/// Benchmark for Cell operations.
///
/// Measures the public Cell endpoint interface, where each emission becomes
/// the safely published current value.
///
/// The update benchmarks include `await()` so the measured operation includes
/// the cell's worker-thread processing and safe-publication store, not just
/// caller-side pipe submission.
///
/// Payloads are pre-allocated reference tokens rather than boxed integers. This
/// keeps Integer boxing, unboxing, and arithmetic result allocation out of the
/// publication-path measurement.
///

@SuppressWarnings ( "ClassEscapesDefinedScope" )
@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class CellOps
  implements Substrates {

  private static final String  NAME_STR   = "cell";
  private static final int     BATCH_SIZE = 10000;
  private static final Value   INITIAL    = new Value ( -1 );
  private static final Value[] VALUES     = values ();

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex  cortex;
  private Name    name;
  private Circuit circuit;

  private Cell < Value > cellSeeded;

  private Pipe < Value > cellPipe;
  private Pipe < Value > emptyPipe;

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
  // BASELINES
  //

  ///
  /// Empty pipe batch with await. Comparison baseline for Cell update batches.
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

  // CELL CREATION
  //

  //
  // ACCESSORS
  //

  ///
  /// Reads a seeded cell.
  ///

  @Benchmark
  public Value get_cell_seeded () {

    return
      cellSeeded.get ();

  }

  ///
  /// Returns the cached pipe for a cell.
  ///

  @Benchmark
  public Pipe < Value > pipe_cell_cached () {

    return
      cellSeeded.pipe ();

  }

  //
  // UPDATES
  //

  @Setup ( Iteration )
  public void setupIteration () {

    circuit =
      cortex.circuit (
        name
      );

    cellSeeded =
      circuit.cell (
        INITIAL
      );

    cellPipe =
      cellSeeded.pipe ();

    deliveries.take ();

    emptyPipe =
      circuit.pipe ( _ -> deliveries.increment () );

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

  ///
  /// Publishes a batch and waits once for publication.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public Value update_cell_batch () {

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      cellPipe.emit (
        VALUES[i]
      );
    }

    circuit.await ();

    return
      cellSeeded.get ();

  }

  record Value(
    int value
  ) {
  }

}
