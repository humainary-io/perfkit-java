// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for `Flow.window(int)` and the `Substrates.Window` view algebra.
///
/// Measures the hot-path cost of the bounded rolling window operator and the
/// per-emission cost of consuming the emitted `Substrates.Window` value via
/// eager `forEach(...)` traversal and derived views (`prefix`, `suffix`, `slice`,
/// `skip`, `trim`, `reversed`).
///
/// ## Benchmark Categories
///
/// 1. **Emission**: `window(4)`, `window(16)`, `window(64)` — pure upstream
///    cost (ring `add` + receptor dispatch) with a no-op sink. Sweeps capacity
///    to validate that the per-emission cost is independent of ring size.
///    Duration windows include both a non-expiring path and an aggressive
///    expiry path.
/// 2. **Consumption**: receptor reads the emitted window via `size()` or
///    `forEach(...)` — measures downstream traversal cost in the pipeline.
/// 3. **View ops**: receptor allocates a derived view (`prefix`, `suffix`,
///    `slice`, `skip`, `trim`, `reversed`) and iterates it — measures the
///    allocate-then-iterate pattern.
/// 4. **Composition**: `map(...).window(N)` and `map(filter).window(N)` —
///    measures the window operator under the surviving-only pipeline path
///    used in real configurations.
/// 5. **Attachment**: pipe materialization cost for the size and duration
///    windows. The `Flow.window(int)` builder cost is not measured — the
///    recipe is assembled once at wiring time.
///
/// Consumption and view benchmarks accept a `Blackhole` parameter and record the
/// result of reading the emitted window into a [Tally], which the benchmark
/// consumes after the drain; this keeps the receptor work observable to JMH and
/// prevents dead-code elimination. Emission benchmarks use a no-op sink and
/// measure only the upstream operator path, matching the convention used by
/// [FlowOps] and [ScanOps].
///
/// The result is held in a [Tally] rather than in a field of this state object,
/// matching the convention [PipeOps] and [MirrorOps] follow. These benchmarks are
/// where that convention was tested: a three-fork decision run against an
/// otherwise identical build using a plain `long` field resolved no difference on
/// any row. The holder stays for uniformity, and because it keeps the traversal
/// accumulator out of a separate object, not because removing it was measured to
/// cost anything — see [Tally] for the numbers.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class WindowOps
  implements Substrates {

  private static final String    NAME_STR   = "test";
  private static final int       VALUE      = 42;
  private static final int       BATCH_SIZE = 10000;
  private static final Integer   PAYLOAD    = VALUE;
  private static final Integer[] PAYLOADS   = payloads ();

  private static final int                                  WINDOW_4     = 4;
  private static final int                                  WINDOW_16    = 16;
  private static final int                                  WINDOW_64    = 64;
  private static final Duration                             WINDOW_LONG  =
    Duration.ofDays (
      1L
    );
  private static final Duration                             WINDOW_SHORT =
    Duration.ofNanos (
      1L
    );
  /// Receptor results, written by the circuit context and held off this object.
  private final        Tally                                output       =
    new Tally ();
  /// Terminal deliveries, counted separately from the recorded result.
  private final        Tally                                deliveries   =
    new Tally ();
  /// Element sink for `forEach` traversal, accumulating through [#output].
  private final        Consumer < Integer >                 adder        =
    output::add;
  private              Cortex                               cortex;
  private              Name                                 name;
  private              Circuit                              circuit;
  // Emission throughput — vary capacity, no-op sink
  private              Pipe < Integer >                     window4Pipe;
  private              Pipe < Integer >                     window16Pipe;
  private              Pipe < Integer >                     window64Pipe;
  private              Pipe < Integer >                     windowDuration16Pipe;
  private              Pipe < Integer >                     windowDurationExpiringPipe;
  // Consumption — receptor traverses the emitted window
  private              Pipe < Integer >                     windowSizePipe;
  private              Pipe < Integer >                     windowDurationSizePipe;
  private              Pipe < Integer >                     windowForEachPipe;
  // View ops — receptor allocates a derived view and iterates it
  private              Pipe < Integer >                     windowPrefixPipe;
  private              Pipe < Integer >                     windowSuffixPipe;
  private              Pipe < Integer >                     windowSlicePipe;
  private              Pipe < Integer >                     windowSkipPipe;
  private              Pipe < Integer >                     windowTrimPipe;
  private              Pipe < Integer >                     windowReversePipe;
  // Terminal aggregators — receptor evaluates a single-result terminal op
  private              Pipe < Integer >                     windowIsEmptyPipe;
  private              Pipe < Integer >                     windowAllPipe;
  private              Pipe < Integer >                     windowAnyPipe;
  private              Pipe < Integer >                     windowNonePipe;
  private              Pipe < Integer >                     windowCountPipe;
  private              Pipe < Integer >                     windowFoldPipe;
  private              Pipe < Integer >                     windowReducePipe;
  // Composition — window after map / filter
  private              Pipe < Integer >                     mapThenWindowPipe;
  private              Pipe < Integer >                     filterThenWindowPipe;
  // Sink for creation benchmarks — typed to the window output so the
  // pipe_create_window benchmark only measures attach + materialization.
  private              Pipe < Window < Integer > >          sink;
  /// Recipes assembled once at wiring time; only attachment is measured.
  private              Flow < Integer, Window < Integer > > windowFlow;
  private              Flow < Integer, Window < Integer > > windowDurationFlow;


  /// Precomputed alternating payloads preserve filter/count branch behavior
  /// without boxing new Integer emissions inside benchmark loops.
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
  /// `map(i -> even ? i : null).window(16)` — measures the surviving-only
  /// path documented for `window`. Roughly half the emissions are filtered
  /// upstream and never reach the ring.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void filter_then_window_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      BATCH_SIZE / 2
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      filterThenWindowPipe.emit ( PAYLOADS[i] );
    }

    circuit.await ();

  }

  ///
  /// `map(i -> i + 1).window(16)` — measures the window operator when
  /// preceded by a stateless type-preserving map. All emissions survive,
  /// so the upstream stage is pure overhead.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void map_then_window_batch (
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
      mapThenWindowPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }


  // =============================
  // CONSUMPTION BENCHMARKS
  // =============================

  ///
  /// Pipe attachment of a `window(int)` Flow — materializes the
  /// per-attachment rolling-window state slot (`Windows.of(count)`) in addition
  /// to the wrapper construction cost.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_window () {

    return
      windowFlow.pipe ( sink );

  }

  ///
  /// Pipe attachment of a `window(Duration, int)` Flow — materializes the
  /// value ring and parallel timestamp array.
  ///

  @Benchmark
  public Pipe < Integer > pipe_create_window_duration () {

    return
      windowDurationFlow.pipe ( sink );

  }

  @Setup ( Iteration )
  public void setupIteration () {

    circuit =
      cortex.circuit (
        name
      );

    output.take ();
    deliveries.take ();

    window4Pipe = attach (
      cortex.flow ( Integer.class ).window ( WINDOW_4 )
    );

    window16Pipe = attach (
      cortex.flow ( Integer.class ).window ( WINDOW_16 )
    );

    window64Pipe = attach (
      cortex.flow ( Integer.class ).window ( WINDOW_64 )
    );

    windowDuration16Pipe = attach (
      cortex.flow ( Integer.class ).window (
        WINDOW_LONG,
        WINDOW_16
      )
    );

    windowDurationExpiringPipe = attach (
      cortex.flow ( Integer.class ).window (
        WINDOW_SHORT,
        WINDOW_16
      )
    );

    windowSizePipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window -> output.set ( window.size () )
    );

    windowDurationSizePipe = attachConsuming (
      cortex.flow ( Integer.class ).window (
        WINDOW_LONG,
        WINDOW_16
      ),
      window -> output.set ( window.size () )
    );

    windowForEachPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      this::sumInto
    );

    windowPrefixPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window ->
        sumInto (
          window.prefix (
            2
          )
        )
    );

    windowSuffixPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window ->
        sumInto (
          window.suffix (
            2
          )
        )
    );

    windowSlicePipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window ->
        sumInto (
          window.slice (
            1,
            2
          )
        )
    );

    windowSkipPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window ->
        sumInto (
          window.skip (
            1
          )
        )
    );

    windowTrimPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window ->
        sumInto (
          window.trim (
            1
          )
        )
    );

    windowReversePipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window ->
        sumInto (
          window.reverse ()
        )
    );

    windowIsEmptyPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window -> output.set ( window.isEmpty () ? 0L : 1L )
    );

    windowAllPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window -> output.set ( window.all ( v -> v >= 0 ) ? 1L : 0L )
    );

    windowAnyPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window -> output.set ( window.any ( v -> v < 0 ) ? 1L : 0L )
    );

    windowNonePipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window -> output.set ( window.none ( v -> v < 0 ) ? 1L : 0L )
    );

    windowCountPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window -> output.set ( window.count ( v -> ( v & 1 ) == 0 ) )
    );

    windowFoldPipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window -> output.set ( window.fold ( 0L, Long::sum ) )
    );

    windowReducePipe = attachConsuming (
      cortex.flow ( Integer.class ).window ( WINDOW_16 ),
      window -> output.set ( window.reduce ( 0, Integer::sum ) )
    );

    mapThenWindowPipe = attach (
      cortex.flow ( Integer.class )
        .map ( i -> i + 1 )
        .window ( WINDOW_16 )
    );

    filterThenWindowPipe = attach (
      cortex.flow ( Integer.class )
        .map ( i -> ( i & 1 ) == 0 ? i : null )
        .window ( WINDOW_16 )
    );

    sink =
      circuit.pipe ();

    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    windowFlow =
      cortex.flow ( Integer.class ).window ( WINDOW_16 );

    windowDurationFlow =
      cortex.flow ( Integer.class ).window (
        WINDOW_LONG,
        WINDOW_16
      );

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
  /// window(16) — canonical mid-size ring.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_16_batch (
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
      window16Pipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }


  // =============================
  // VIEW BENCHMARKS
  // =============================

  ///
  /// window(4) — small ring, write-through is the dominant cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_4_batch (
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
      window4Pipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// window(64) — larger ring. Per-emission cost should not depend on
  /// capacity since `Buffer.add` is O(1) regardless of size.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_64_batch (
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
      window64Pipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// `all(v -> v >= 0)` — full traversal under a predicate that never
  /// short-circuits for the constant payload; measures the per-element
  /// predicate-test path against the same ring as `forEach` and `count`.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_all_batch (
    final Blackhole bh,
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
      windowAllPipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `any(v -> v < 0)` — full traversal under a predicate that never matches
  /// for the constant payload; mirror of `all` with the opposite
  /// short-circuit polarity.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_any_batch (
    final Blackhole bh,
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
      windowAnyPipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `count(v -> (v & 1) == 0)` — full traversal counting every value
  /// whose lower bit is clear. Predicate evaluates to `true` for half
  /// the visible values.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_count_batch (
    final Blackhole bh,
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
      windowCountPipe.emit ( PAYLOADS[i] );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `window(Duration.ofDays(1), 16)` — duration-window hot path where no
  /// entries expire by time during the batch. Measures timestamp capture,
  /// cutoff calculation, side-array timestamp write, and ring append.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_duration_16_batch (
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
      windowDuration16Pipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// `window(Duration.ofNanos(1), 16)` — duration-window path that strongly
  /// favors time expiry between emissions. Measures the skip/trim path in
  /// addition to timestamp capture and append.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_duration_expiring_batch (
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
      windowDurationExpiringPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// `window(Duration.ofDays(1), 16).size()` — duration-window emission plus
  /// the cheapest downstream read against the emitted view.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_duration_size_batch (
    final Blackhole bh,
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
      windowDurationSizePipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `fold(0L, (acc,v) -> acc + v)` — type-changing fold into a `Long`
  /// accumulator. Compares against `reduce` (same-type) and `forEach`
  /// (no accumulator).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_fold_batch (
    final Blackhole bh,
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
      windowFoldPipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// Iterate the emitted window via `forEach(...)`, summing every value.
  /// This is the pipeline-consumption shape that uses the Window-specific
  /// traversal path and bypasses iterator allocation.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_for_each_batch (
    final Blackhole bh,
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
      windowForEachPipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `isEmpty()` — cheapest fast-path read against the emitted window
  /// (a single field compare). Reference point for predicate-free
  /// terminal access.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_is_empty_batch (
    final Blackhole bh,
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
      windowIsEmptyPipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }


  // =============================
  // COMPOSITION BENCHMARKS
  // =============================

  ///
  /// `none(v -> v < 0)` — full traversal under a predicate that never matches
  /// for the constant payload; mirror of `all` and `any` at the same
  /// per-element cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_none_batch (
    final Blackhole bh,
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
      windowNonePipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `prefix(2)` then iterate — exercises the `Slice` view allocation and
  /// the forward-step traversal over a sub-range.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_prefix_batch (
    final Blackhole bh,
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
      windowPrefixPipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `reduce(0, Integer::sum)` — same-type fold over the visible
  /// values. Counterpart to `fold` with an `E`-typed accumulator;
  /// measures the standard sum-style reduction shape.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_reduce_batch (
    final Blackhole bh,
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
      windowReducePipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `reverse()` then iterate — exercises the reversed view, which
  /// inverts the source step and traverses the ring backward (using the
  /// negative-fixup branch of `offset(...)`).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_reverse_batch (
    final Blackhole bh,
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
      windowReversePipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// window.size() — measures the fast-path read of the current view size.
  /// Reference point for the cheapest receptor operation against the
  /// emitted window value.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_size_batch (
    final Blackhole bh,
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
      windowSizePipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }


  // =============================
  // CREATION BENCHMARKS
  // =============================

  ///
  /// `skip(1)` then iterate — exercises the open-ended slice path
  /// (count = MAX_VALUE), expected to be cheaper than `slice(1, n)` only
  /// at allocation time.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_skip_batch (
    final Blackhole bh,
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
      windowSkipPipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `slice(1, 2)` then iterate — exercises `Slice` with a non-zero offset.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_slice_batch (
    final Blackhole bh,
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
      windowSlicePipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }


  ///
  /// `suffix(2)` then iterate — exercises the `Suffix` view allocation, which
  /// computes its start from the source's size to anchor at the newer end.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_suffix_batch (
    final Blackhole bh,
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
      windowSuffixPipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  ///
  /// `trim(1)` then iterate — exercises the `Trim` view (preserves source
  /// start, shrinks size). Reference for the cheapest derived-view shape.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void window_trim_batch (
    final Blackhole bh,
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
      windowTrimPipe.emit ( PAYLOAD );
    }

    circuit.await ();

    bh.consume ( output.take () );

  }

  private < O > Pipe < Integer > attach (
    final Flow < Integer, O > flow
  ) {

    final var tally =
      deliveries;

    return
      flow.pipe ( circuit.pipe ( _ -> tally.increment () ) );

  }

  private Pipe < Integer > attachConsuming (
    final Flow < Integer, Window < Integer > > flow,
    final Receptor < ? super Window < Integer > > receptor
  ) {

    final var tally =
      deliveries;

    return
      flow.pipe (
        circuit.pipe ( ( Window < Integer > window ) -> {
          tally.increment ();
          receptor.receive ( window );
        } )
      );

  }

  /// Traverses `window` via `forEach`, accumulating every element into
  /// [#output]. The running total lives in the padded holder rather than in a
  /// helper object of its own: an unpadded accumulator allocated alongside this
  /// state object can share a cache line with the pipe references the measured
  /// loop reads.

  private void sumInto (
    final Window < Integer > window
  ) {

    output.take ();

    window.forEach (
      adder
    );

  }

}
