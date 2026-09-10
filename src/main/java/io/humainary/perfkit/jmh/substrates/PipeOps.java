// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Pipe operations.
///
/// Measures the core emission hot path - the most performance-critical operation
/// in the substrate framework. Async pipes dispatch through the circuit's queue
/// to its virtual thread.
///
/// ## Benchmark Categories
///
/// 1. **Batch emission**: Measures throughput with amortized overhead
/// 2. **Fan-out**: Measures emission to multiple receptors
/// 3. **Chained pipes**: Measures pipe-to-pipe forwarding
/// 4. **Flow operations**: Measures overhead of flow operators on hot path
///
/// Benchmarks that claim completed processing declare a [TerminalVerification]
/// parameter so their invocation fixtures do not apply to the creation and
/// baseline benchmarks in this class.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class PipeOps
  implements Substrates {

  private static final String NAME_STR   = "test";
  private static final int    VALUE      = 42;
  private static final int    BATCH_SIZE = 10000;

  // Pre-boxed payloads: emitting a shared Integer reference keeps the emit loops
  // allocation-free (the autobox cache only covers -128..127, so `VALUE + i` would
  // allocate a fresh Integer almost every iteration), so the benchmarks measure
  // dispatch cost rather than per-emission boxing. PAYLOAD_ALT lets the diff() flow
  // benchmark alternate values so every emission still passes the dedup.
  private static final Integer           PAYLOAD     = VALUE;
  private static final Integer           PAYLOAD_ALT = VALUE + 1;
  // Terminal deliveries counted by the circuit context, held off this state object.
  private final        Tally             deliveries  = new Tally ();
  private              Cortex            cortex;
  private              Name              name;
  private              Circuit           circuit;
  // Pre-created pipes for hot path measurement
  private              Pipe < Integer >  asyncPipe;
  private              Pipe < Integer >  asyncPipeWithFlow;
  private              Pipe < Integer >  chainedPipe;
  private              Pipe < Integer >  fanOutPipe;
  private              Pipe < Integer >  staticFanOutPipe;
  private              Pipe < Integer >  emptyPipe;
  // Sink for collecting emissions (no-op receptor)
  private              Pipe < Integer >  sink;
  /// Recipe assembled once at wiring time; only its attachment is measured.
  private              Fiber < Integer > guardDiffFiber;
  // Counter for verifying emissions
  private              AtomicInteger     counter;

  //
  // ASYNC PIPE BENCHMARKS
  // Async pipes dispatch through the circuit's queue to its virtual thread.
  // This is the standard hot path for the substrate framework.
  //

  ///
  /// Blackhole control - measures Blackhole.consume() cost.
  /// This is a diagnostic control and is not subtracted from pipe scores.
  ///

  @Benchmark
  public static void baseline_blackhole (
    final Blackhole bh
  ) {

    bh.consume ( VALUE );

  }

  ///
  /// Batch caller-side admissions. Circuit completion and terminal verification
  /// occur in invocation teardown, outside the primary JMH timer.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void async_emit_admission_batch (
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
      asyncPipe.emit ( PAYLOAD );
    }

  }

  ///
  /// Batch async emissions followed by one complete circuit drain.
  /// Normalized per source admission, not per terminal delivery.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void async_emit_batch (
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
      asyncPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  //
  // FLOW OPERATION BENCHMARKS
  // Measures overhead of flow operators in the hot path.
  //

  ///
  /// Chained pipe emission - measures pipe forwarding cost.
  /// Source pipe emits to intermediate pipe, which emits to sink.
  /// Tests transit queue behavior (cascading emissions).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void async_emit_chained_batch (
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
      chainedPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  //
  // CHAINED PIPE BENCHMARKS
  // Measures pipe-to-pipe forwarding for neural-like networks.
  //

  ///
  /// Fan-out emission - measures multi-receptor dispatch.
  /// Single emission triggers 3 receptor invocations.
  /// Tests inlet iteration and receptor caching. The score stays normalized per
  /// source admission, not per terminal delivery, so it is comparable to the
  /// single-receptor batch; teardown verifies the three deliveries.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void async_emit_fanout_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE * 3L
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      fanOutPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  //
  // FAN-OUT BENCHMARKS
  // Measures emission to multiple receptors (pub-sub pattern).
  //
  //
  // PIPE CREATION BENCHMARKS
  // Measures cost of creating new pipes (not hot path, but affects startup).
  //

  ///
  /// Async emission through flow (guard + diff).
  /// Measures cost of flow operations during emission processing.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void async_emit_with_flow_batch (
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
      asyncPipeWithFlow.emit ( ( i & 1 ) == 0 ? PAYLOAD : PAYLOAD_ALT );
    }

    circuit.await ();

  }

  ///
  /// Counter baseline - measures AtomicInteger.incrementAndGet() cost.
  /// Represents minimal "real work" in a receptor.
  ///

  @Benchmark
  public int baseline_counter () {

    return counter.incrementAndGet ();

  }

  //
  // COMPARISON BENCHMARKS
  // Measures baseline costs for comparison.
  //

  ///
  /// Batch empty-pipe emissions followed by one complete drain, with a NOOP
  /// receptor. Comparison baseline for `async_emit_batch`.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void empty_emit_batch () {

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      emptyPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Async pipe creation - measures circuit.pipe() factory cost.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create () {

    return
      circuit.pipe (
        Receptor.of ( Integer.class )
      );

  }

  ///
  /// Empty pipe creation - measures circuit.pipe() factory cost.
  /// Comparison baseline for `pipe_create` (circuit.pipe(Receptor.of())).
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_empty () {

    return
      circuit.pipe ();

  }

  ///
  /// Static fan-out pipe creation - measures circuit.pipe(List) factory cost,
  /// including provider validation and per-target same-circuit resolution.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_fanout () {

    return
      circuit.pipe (
        List.of ( sink, sink, sink )
      );

  }

  ///
  /// Named static fan-out pipe creation - measures circuit.pipe(Name, List) factory
  /// cost, including provider validation, per-target resolution, and name binding.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_named_fanout () {

    return
      circuit.pipe (
        name,
        List.of ( sink, sink, sink )
      );

  }

  ///
  /// Async pipe creation with flow - measures flow configuration overhead.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_with_flow () {

    return
      guardDiffFiber.pipe (
        circuit.pipe ( Receptor.of ( Integer.class ) )
      );

  }

  ///
  /// Same-circuit target resolution — measures the short-circuit in
  /// `circuit.pipe(Pipe)`, which returns the target unchanged when the target
  /// already belongs to this circuit's valve and only allocates a forwarding
  /// pipe when it does not.
  ///
  /// This is deliberately not named as a creation benchmark: on this path no
  /// pipe is created. What it prices is the null check, the open check, and the
  /// valve comparison that make same-circuit chaining free — which is why it
  /// reads about a nanosecond against the ~9 ns of the allocating factories.
  /// A cross-circuit target would take the allocating branch and is a separate
  /// measurement, not this one.
  ///

  @Benchmark
  public Pipe < Integer > pipe_target_same_circuit () {

    return
      circuit.pipe (
        sink
      );

  }

  //
  // STATIC FAN-OUT BENCHMARKS
  // Measures circuit.pipe(List) dispatch to a fixed set of pre-resolved targets.
  //
  // This is a different topology from the conduit fan-out, not a baseline for
  // it, and the two scores are not each other's control. A conduit inlet holds
  // receptors and invokes all three inline on the worker, so one admission is
  // one queue traversal. A circuit.pipe(List) holds *pipes*, and every pipe a
  // circuit hands out queues on emit, so one admission becomes one ingress
  // traversal plus one transit traversal per target. The API cannot express the
  // cheaper shape here: a list target must be a runtime-provided pipe, so there
  // is no non-queueing terminal to fan out to. The gap between these two rows
  // is therefore the cost of re-queueing per target, and it is a property of
  // the two constructs rather than of the benchmark.
  //

  @Setup ( Iteration )
  public void setupIteration () {

    counter =
      new AtomicInteger ();

    deliveries.take ();

    circuit =
      cortex.circuit (
        name
      );

    // Counted sink for pipe chaining and creation targets.
    sink =
      circuit.pipe (
        countedReceptor ()
      );

    // Async pipe - queue dispatch
    asyncPipe =
      circuit.pipe (
        countedReceptor ()
      );

    // Empty pipe - queue dispatch, NOOP receptor
    emptyPipe =
      circuit.pipe ();

    // Async pipe with flow operations
    asyncPipeWithFlow =
      cortex.fiber ( Integer.class ).guard ( v -> v > 0 ).diff ()
        .pipe ( circuit.pipe ( countedReceptor () ) );

    // Chained pipe - pipe to pipe forwarding
    final var intermediate =
      circuit.pipe (
        sink
      );

    chainedPipe =
      circuit.pipe (
        intermediate
      );

    // Fan-out pipe via conduit with multiple subscribers
    final var conduit =
      circuit.conduit (
        Integer.class
      );

    // Subscribe 3 receptors to create fan-out
    conduit.subscribe (
      circuit.subscriber (
        cortex.name ( "fanout" ),
        ( subject, registrar ) -> {
          registrar.register ( countedReceptor () );
          registrar.register ( countedReceptor () );
          registrar.register ( countedReceptor () );
        }
      )
    );

    // Get the channel's pipe for emission via the conduit
    fanOutPipe =
      conduit.get ( name );

    // Static fan-out pipe - circuit.pipe(List) to 3 fixed same-circuit sinks
    staticFanOutPipe =
      circuit.pipe (
        List.of (
          circuit.pipe ( countedReceptor () ),
          circuit.pipe ( countedReceptor () ),
          circuit.pipe ( countedReceptor () )
        )
      );

    // Warm up the circuit
    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    guardDiffFiber =
      cortex.fiber ( Integer.class ).guard ( v -> v > 0 ).diff ();

    name =
      cortex.name (
        NAME_STR
      );

  }

  ///
  /// Batch static fan-out emissions with await - measures dispatch to 3
  /// pre-resolved same-circuit outlets per emission, normalized per source
  /// admission rather than per terminal delivery.
  ///
  /// Each outlet is a circuit pipe and therefore queues, so one admission here
  /// costs four queue traversals against the conduit fan-out's one. Do not read
  /// the difference from `async_emit_fanout_batch` as a dispatch-strategy
  /// comparison — see the section comment above.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void static_fanout_emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE * 3L
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      staticFanOutPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

  private Receptor < Integer > countedReceptor () {

    final var tally =
      deliveries;

    return
      _ -> tally.increment ();

  }

}
