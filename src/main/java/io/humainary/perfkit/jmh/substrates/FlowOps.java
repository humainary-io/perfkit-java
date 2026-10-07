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
/// Benchmark for Flow type-changing operators.
///
/// Measures the hot-path cost of the basic Flow composition subset
/// (`map`, `fiber`, `flow`) when attached to a Pipe. `scan` is covered
/// separately in [ScanOps], and Fiber per-emission operators are covered
/// separately in [FiberOps].
///
/// ## Benchmark Categories
///
/// 1. **Identity**: Plain `cortex.flow()` — pure Forwarder (baseline)
/// 2. **map**: Integer → Integer trivial transform
/// 3. **map chained**: Two stacked maps
/// 4. **fiber**: Flow with a same-type Fiber attached at the output side
/// 5. **Attachment**: `flow.pipe(target)` materialization cost. Building the
///    Flow recipe itself is not measured — it is assembled once at wiring
///    time, whereas attachment recurs per subject in a pooled conduit.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class FlowOps
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

  // Baseline: plain async pipe (no flow)
  private Pipe < Integer > plainPipe;

  // Identity flow (Forwarder)
  private Pipe < Integer > identityPipe;

  // map
  private Pipe < Integer > mapPipe;
  private Pipe < Integer > mapChainedPipe;

  // fiber
  private Pipe < Integer > fiberPipe;

  // fiber via subject-aware factory (Flow.fiber(Function<Subject, Fiber>))
  private Pipe < Integer > fiberFactoryPipe;

  // composed — Flow.flow(Flow) materialization path (Flows.Composed)
  private Pipe < Integer > composedPipe;

  // Sink for creation benchmarks
  private Pipe < Integer >          sink;
  /// Recipes assembled once at wiring time; only attachment is measured.
  private Flow < Integer, Integer > fiberFactoryFlow;
  private Flow < Integer, Integer > mapFlow;

  // =============================
  // EMISSION BENCHMARKS
  // =============================

  ///
  /// Baseline: plain async pipe batch emission with await.
  /// Reference for measuring flow overhead.
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
  /// composed — two flows joined via `Flow.flow(Flow)` (materializes via
  /// Flows.Composed.receptor() → `first.receptor(second.receptor(downstream))`).
  /// Distinct from `mapChainedPipe` which stacks maps via `pipe(pipe(target))`.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void composed_batch (
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
      composedPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// fiber — a Fiber (guard) attached at the output side of an identity flow.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void fiber_batch (
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
      fiberPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// fiber via subject-aware factory — a per-attachment factory produces
  /// the same guard fiber as the static `fiber` benchmark. Steady-state
  /// emission cost should match `fiber_batch` since the factory
  /// is invoked once at attachment, not per emission.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void fiber_factory_batch (
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
      fiberFactoryPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Identity flow — attaches Forwarder, no transformation.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void identity_batch (
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
      identityPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  // =============================
  // CREATION BENCHMARKS
  // =============================

  ///
  /// map — single Integer → Integer transform.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void map_batch (
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
      mapPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// map chained — two stacked transforms.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void map_chained_batch (
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
      mapChainedPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Pipe attachment of a fiber-factory Flow — exercises the per-attachment
  /// factory invocation in addition to the wrapper construction cost.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_fiber_factory () {

    return
      fiberFactoryFlow.pipe ( sink );

  }

  ///
  /// Pipe attachment of a mapping Flow.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_map () {

    return
      mapFlow.pipe ( sink );

  }

  @Setup ( Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit (
        name
      );

    plainPipe =
      circuit.pipe (
        _ -> deliveries.increment ()
      );

    identityPipe = attach ( cortex.flow ( Integer.class ) );

    mapPipe = attach (
      cortex.flow ( Integer.class ).map ( i -> i + 1 )
    );

    mapChainedPipe = chainedMap ();

    fiberPipe = attach (
      cortex.flow ( Integer.class )
        .fiber ( cortex.fiber ( Integer.class ).guard ( v -> v > 0 ) )
    );

    fiberFactoryPipe = attach (
      cortex.flow ( Integer.class )
        .fiber (
          subject -> cortex.fiber ( Integer.class ).guard ( v -> v > 0 )
        )
    );

    composedPipe = attach (
      cortex.flow ( Integer.class ).map ( i -> i + 1 )
        .flow ( cortex.flow ( Integer.class ).map ( i -> i * 2 ) )
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

    fiberFactoryFlow =
      cortex.flow ( Integer.class )
        .fiber (
          subject -> cortex.fiber ( Integer.class ).guard ( v -> v > 0 )
        );

    mapFlow =
      cortex.flow ( Integer.class ).map ( i -> i + 1 );

    name =
      cortex.name (
        NAME_STR
      );

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

  private Pipe < Integer > attach (
    final Flow < Integer, Integer > flow
  ) {

    return
      flow.pipe ( circuit.pipe ( _ -> deliveries.increment () ) );

  }

  private Pipe < Integer > chainedMap () {

    final Pipe < Integer > target =
      circuit.pipe ( _ -> deliveries.increment () );

    return
      cortex.flow ( Integer.class ).map ( i -> i + 1 ).pipe (
        cortex.flow ( Integer.class ).map ( i -> i + 1 ).pipe ( target )
      );

  }

}
