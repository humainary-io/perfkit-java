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
/// Benchmark for `Flow.scan` and `Flow.flow(Function<Subject, Flow>)` —
/// type-changing stateful folds and canonical subject-aware composition.
///
/// ## Benchmark Categories
///
/// 1. **scan**: state-only emit projection (`Function<S, P>`)
/// 2. **scan input-aware**: emit projection (`BiFunction<S, O, P>`)
/// 3. **flow factory**: `Flow.flow(Function<Subject, Flow>)` composition
/// 4. **scan via flow factory**: subject-aware scan composition pattern
/// 5. **Attachment**: `pipe(target)` materialization cost
///
/// Compares against `FlowOps.map_batch` (stateless type-changing
/// baseline) for shape parity and `FiberOps.reduce_*` (stateful
/// type-preserving fold) for stateful-operator overhead.
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class ScanOps
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

  // scan with state-as-output (running sum, S=P=Integer)
  private Pipe < Integer > scanRunningSumPipe;

  // scan with state ≠ output (running mean, S=long[], P=Double)
  private Pipe < Integer > scanRunningMeanPipe;

  // scan input-aware emit (S, current input) -> P
  private Pipe < Integer > scanInputAwarePipe;

  // scan composed under flow.flow(factory) — subject-aware seed pattern
  private Pipe < Integer > scanViaFlowFactoryPipe;

  // Sink for creation benchmarks
  private Pipe < Integer >          sink;
  /// Recipes assembled once at wiring time; only attachment is measured.
  private Flow < Integer, Integer > flowFactoryFlow;
  private Flow < Integer, Integer > scanFlow;


  // =============================
  // EMISSION BENCHMARKS
  // =============================

  ///
  /// Pipe attachment of a flow-factory Flow — exercises the per-attachment
  /// factory invocation in addition to the wrapper construction cost.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_flow_factory () {

    return
      flowFactoryFlow.pipe ( sink );

  }


  // =============================
  // CREATION BENCHMARKS
  // =============================

  ///
  /// Pipe attachment of a scan Flow — exercises Supplier invocation
  /// (per-attachment state allocation) plus wrapper construction.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_scan () {

    return
      scanFlow.pipe ( sink );

  }

  ///
  /// Input-aware scan: emit projection sees both state and current input.
  /// Measures overhead of the BiFunction emit path vs. the state-only
  /// Function emit path.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void scan_inputAware_batch (
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
      scanInputAwarePipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Running mean via scan with separate state and output types
  /// (Integer in, long[] state, Double out). Exercises the load-bearing
  /// case for `scan` — the reason the operator exists.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void scan_runningMean_batch (
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
      scanRunningMeanPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Running sum via scan with state-as-output (Integer → Integer).
  /// Reference for stateful fold cost where state and output share type.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void scan_runningSum_batch (
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
      scanRunningSumPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Scan composed under Flow.flow(Function<Subject, Flow>) — the canonical
  /// subject-aware scan pattern. Steady-state emission cost should match
  /// the bare scan path since the factory is invoked once at attachment.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void scan_via_flow_factory_batch (
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
      scanViaFlowFactoryPipe.emit ( PAYLOAD );
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

    scanRunningSumPipe = attach (
      cortex.flow ( Integer.class )
        .scan ( () -> 0, Integer::sum, s -> s )
    );

    scanRunningMeanPipe = attach (
      cortex.flow ( Integer.class )
        .scan (
          () -> new long[]{0L, 0L},
          ( s, v ) -> {
            s[0] += v;
            s[1] += 1L;
            return s;
          },
          s -> s[1] == 0L ? null : (double) s[0] / s[1]
        )
    );

    scanInputAwarePipe = attach (
      cortex.flow ( Integer.class )
        .scan (
          () -> 0,
          Integer::sum,
          Integer::sum
        )
    );

    scanViaFlowFactoryPipe = attach (
      cortex.flow ( Integer.class )
        .flow ( subject ->
          cortex.flow ( Integer.class )
            .scan ( () -> 0, Integer::sum, s -> s )
        )
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

    flowFactoryFlow =
      cortex.flow ( Integer.class )
        .flow (
          subject -> cortex.flow ( Integer.class ).map ( i -> i + 1 )
        );

    scanFlow =
      cortex.flow ( Integer.class )
        .scan ( () -> 0, Integer::sum, s -> s );

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
