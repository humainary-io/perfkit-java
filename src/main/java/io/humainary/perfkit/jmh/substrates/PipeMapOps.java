// Copyright (c) 2025 William David Louth

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
/// Benchmark for `Flow.map(Function)` composed via `Flow.pipe(Pipe)` — type-transforming pipe composition.
///
/// Measures the overhead of the Transform receptor wrapper relative to
/// a plain async pipe. All transforms use trivial integer arithmetic
/// to isolate the framework overhead from function cost.
///
/// ## Benchmark Categories
///
/// 1. **Baseline comparison**: Plain async pipe vs mapped pipe
/// 2. **Batch throughput**: Mapped pipe batch emission with await
/// 3. **Chained maps**: Double transformation overhead
/// 4. **Stateful**: Per-stem accumulation pattern
/// 5. **Creation cost**: measured once for the whole suite by
///    `FlowOps.pipe_create_map`, which builds the identical recipe
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class PipeMapOps
  implements Substrates {

  private static final String  NAME_STR   = "test";
  private static final int     VALUE      = 42;
  private static final int     BATCH_SIZE = 10000;
  private static final Integer PAYLOAD    = VALUE;

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex  cortex;
  private Name    name;
  private Circuit circuit;

  // Baseline: plain async pipe (no transform)
  private Pipe < Integer > plainPipe;

  // Mapped pipe: Integer → Integer (trivial transform)
  private Pipe < Integer > mappedPipe;

  // Mapped pipe with stateful transform (accumulator)
  private Pipe < Integer > statefulMappedPipe;

  // Double-mapped pipe: Integer → Integer → Integer
  private Pipe < Integer > chainedMappedPipe;

  // Sink for pipe creation benchmarks
  private Pipe < Integer > sink;


  // =============================
  // EMISSION BENCHMARKS
  // =============================

  ///
  /// Baseline: plain async pipe batch emission with await.
  /// Reference for measuring transform overhead.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void baseline_plain_batch (
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
      plainPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Chained mapped pipe batch emission with await.
  /// Transform: i → i + 1 → i + 1 (double transform).
  /// Measures cost of stacked Transform wrappers.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void mapped_chained_batch (
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
      chainedMappedPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Mapped pipe batch emission with await.
  /// Transform: i → i + 1 on circuit thread.
  /// Overhead vs baseline is the cost of Transform.receive() + fn.apply().
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void mapped_emit_batch (
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
      mappedPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Stateful mapped pipe batch emission with await.
  /// Transform: accumulates values on circuit thread.
  /// Measures realistic per-stem aggregation cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void mapped_stateful_batch (
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
      statefulMappedPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }


  // =============================
  // CREATION BENCHMARKS
  // =============================

  ///
  /// `cortex.flow(type).map(fn).pipe(sink)` creation cost.
  /// Measures allocation of new Pipe + Transform wrapper.
  ///


  @Setup ( Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit (
        name
      );

    // Baseline: plain async pipe
    plainPipe =
      circuit.pipe (
        _ -> deliveries.increment ()
      );

    // Mapped: i → i + 1
    final Pipe < Integer > target =
      circuit.pipe (
        _ -> deliveries.increment ()
      );

    mappedPipe =
      cortex.flow ( Integer.class ).map ( i -> i + 1 ).pipe ( target );

    // Stateful: accumulating sum
    final Pipe < Integer > statefulTarget =
      circuit.pipe (
        _ -> deliveries.increment ()
      );

    final var sum = new long[1];

    statefulMappedPipe =
      cortex.flow ( Integer.class ).map ( i -> {
        sum[0] += i;
        return (int) sum[0];
      } ).pipe ( statefulTarget );

    // Chained: i → i + 1 → i + 1
    final Pipe < Integer > chainedTarget =
      circuit.pipe (
        _ -> deliveries.increment ()
      );

    chainedMappedPipe =
      cortex.flow ( Integer.class ).map ( i -> i + 1 ).pipe (
        cortex.flow ( Integer.class ).map ( i -> i + 1 ).pipe ( chainedTarget )
      );

    // Sink for creation benchmarks
    sink =
      circuit.pipe (
        Receptor.of ( Integer.class )
      );

    // Warm up the circuit
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
