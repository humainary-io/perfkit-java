// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.locks.LockSupport.park;
import static java.util.concurrent.locks.LockSupport.unpark;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for contended and sustained ingress admission.
///
/// `PipeOps` admits from the single JMH thread, so its ingress path is never
/// contended and its backlog never outlives one batch. These rows put several
/// producers on one circuit and keep admitting while the worker drains, which is
/// where an ingress design pays for its access policy rather than for its
/// storage layout.
///
/// ## Producers
///
/// Producer threads are started once per trial and parked between rounds, so the
/// only per-invocation coordination is one `unpark` and one round-counter read
/// per producer. They are parked rather than spinning so that an idle producer
/// costs a core nothing while the caller's own burst is timed. The benchmark
/// thread is itself a producer, so [#PRODUCERS] parked threads make
/// `PRODUCERS + 1` concurrent admitters.
///
/// A trial-scoped fixture holds the threads; the circuit is rebuilt per
/// iteration, and producers read it through a volatile field refreshed by
/// iteration setup.
///
/// ## Overflow and overlap
///
/// Each producer admits [#BURST] = 10,000 in a tight loop and four of them run
/// at once, so a backlog forms and persists unless the single worker drains
/// faster than four threads admit — which the completed rates these rows report
/// refute directly. The sustained row raises the burst tenfold so the overflow
/// persists across the whole invocation rather than forming and clearing once.
///
/// How deep a backlog a provider absorbs before it allocates is its own
/// business, and no API states it. These rows are written against the depth,
/// not against a capacity: a provider that holds a ring, one that holds none,
/// and one that sizes a ring differently all run them, and what should be read
/// is the completed rate against the admission rate rather than a boundary.
///
/// No await separates the bursts. Draining between replenishments would empty
/// the queue at exactly the moment the overflow persistence being measured would
/// otherwise show, and the row would report a sequence of cold starts instead.
///
/// ## Reading the rows
///
/// - `ingress_contended_emit_batch` — **completed** throughput: the invocation
///   does not return until every admission has been processed. Per admission.
/// - `ingress_contended_admission_batch` — caller **admission** progress for the
///   same workload, with the drain moved to invocation teardown. A worker that
///   holds a coordination boundary longer to amortize its own work shows up here
///   as degraded caller responsiveness even when completed throughput improves.
/// - `ingress_sustained_emit_400k` — completed throughput against a backlog that
///   never clears until the final drain.
///
/// ## The quiet circuit
///
/// - `ingress_idle_roundtrip` — one emission arriving at a circuit nothing else
///   is admitting to, drained before the next. Reported per round trip, and it
///   is a **wakeup-latency canary rather than an admission cost**; see the row.
///
/// It is the opposite extreme from the contended rows above and shares their
/// fixture: the producer threads park between rounds and no round is started, so
/// it admits alone. It measures an ingress property — how a circuit wakes —
/// which is why it lives here rather than beside the transit storage rows, whose
/// fan-out shapes it has nothing to do with.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class IngressOps
  implements Substrates {

  private static final int     PRODUCERS        = 3;
  private static final int     BURST            = 10000;
  private static final int     CONTENDED_OPS    = BURST * ( PRODUCERS + 1 );
  private static final int     SUSTAINED_BURST  = BURST * 10;
  private static final int     SUSTAINED_OPS    = SUSTAINED_BURST * ( PRODUCERS + 1 );
  private static final Object  PAYLOAD          = new Object ();
  /// Round trips per invocation. Each is one emission and one drain, so the
  /// row normalized by this reports the cost of a round trip, not of an
  /// admission.
  private static final int     ROUNDTRIPS       = 64;

  private final Tally           deliveries = new Tally ();
  private final AtomicInteger   finished   = new AtomicInteger ();
  private final List < Thread > producers  = new ArrayList <> ();

  private          Cortex          cortex;
  private          Circuit         circuit;
  /// Read by every producer at the start of a round; rewritten each iteration.
  private volatile Pipe < Object > shared;
  /// Bumped once per round; producers wake, compare, and admit exactly once.
  private volatile int             round;
  /// How many admissions the current round asks each producer for.
  private volatile int             size;
  private volatile boolean         running    = true;

  ///
  /// One emission arriving at a quiet circuit, drained before the next.
  ///
  /// **A wakeup-latency canary, not an admission cost.** Nearly all of the score
  /// is the park/unpark round trip: admission is tens of nanoseconds against a
  /// round trip of several microseconds, so this row cannot resolve admission
  /// and must never be read as the cost of a first queue position. The rows
  /// above are what price admission.
  ///
  /// What it is for is a failure neither the rest of the suite nor either TCK
  /// would catch. If the idle handshake ever loses a wakeup, the worker still
  /// drains — on its timed park — so the behaviour stays *correct* and only the
  /// latency moves, by roughly three orders of magnitude. This row is what
  /// notices.
  ///
  /// It ran at three burst widths, 1, 4 and 16. Those were one measurement in
  /// three units: the score is `emit + roundtrip / width`, so solving any pair
  /// returns the same round trip, and the `emit` term separating them is a
  /// fraction of a percent of the score — well inside the error, and a reliable
  /// source of false regressions. The other two widths were dropped. Width 1
  /// needs no rescaling: one await per emission means the score already is the
  /// round trip.
  ///

  @Benchmark
  @OperationsPerInvocation ( ROUNDTRIPS )
  public void ingress_idle_roundtrip (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      ROUNDTRIPS
    );

    final var pipe =
      shared;

    for (
      int round = 0;
      round < ROUNDTRIPS;
      round++
    ) {

      pipe.emit ( PAYLOAD );

      circuit.await ();

    }

  }

  ///
  /// Caller admission progress under contention. The drain runs in invocation
  /// teardown, outside the timer, so this row is the producer-side counterpart
  /// of the completed row and not a second measurement of the same thing.
  ///

  @Benchmark
  @OperationsPerInvocation ( CONTENDED_OPS )
  public void ingress_contended_admission_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      CONTENDED_OPS,
      circuit
    );

    round ( BURST );

  }

  ///
  /// Completed throughput under contention: four concurrent admitters, one
  /// worker, one drain at the end of the invocation.
  ///

  @Benchmark
  @OperationsPerInvocation ( CONTENDED_OPS )
  public void ingress_contended_emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      CONTENDED_OPS
    );

    round ( BURST );

    circuit.await ();

  }

  ///
  /// Completed throughput against a backlog that persists for the whole
  /// invocation. Ten times the contended burst, admitted with no intervening
  /// drain, so the queue is in overflow for nearly all of the measured region.
  ///

  @Benchmark
  @OperationsPerInvocation ( SUSTAINED_OPS )
  public void ingress_sustained_emit_400k (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      SUSTAINED_OPS
    );

    round ( SUSTAINED_BURST );

    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit ();

    final var tally =
      deliveries;

    shared =
      circuit.pipe (
        _ -> tally.increment ()
      );

    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    for (
      int i = 0;
      i < PRODUCERS;
      i++
    ) {
      producers.add (
        Thread.ofPlatform ()
          .name ( "ingress-producer-" + i )
          .start ( this::produce )
      );
    }

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.await ();
    circuit.close ();

  }

  @TearDown ( Trial )
  public void tearDownTrial ()
    throws InterruptedException {

    running = false;

    for ( final var producer : producers ) {
      unpark ( producer );
    }

    for ( final var producer : producers ) {
      producer.join ();
    }

    producers.clear ();

  }

  /// Admits `count` emissions from this thread.
  private static void admit (
    final Pipe < Object > pipe,
    final int count
  ) {

    for (
      int i = 0;
      i < count;
      i++
    ) {
      pipe.emit ( PAYLOAD );
    }

  }

  /// Producer loop: park until the round counter moves, admit one burst, repeat.
  /// The round comparison makes a spurious wakeup a no-op.
  private void produce () {

    var seen = 0;

    while ( running ) {

      var current = round;

      while ( current == seen && running ) {
        park ( this );
        current = round;
      }

      if ( !running ) {
        return;
      }

      seen = current;

      admit ( shared, size );

      finished.incrementAndGet ();

    }

  }

  /// Releases every producer for one burst of `count`, admits the caller's own
  /// burst concurrently, and returns once all producers have finished admitting.
  @SuppressWarnings ( "NonAtomicOperationOnVolatileField" )
  private void round (
    final int count
  ) {

    finished.set ( 0 );
    size = count;

    final var pipe =
      shared;

    round++;

    for ( final var producer : producers ) {
      unpark ( producer );
    }

    admit ( pipe, count );

    while ( finished.get () < PRODUCERS ) {
      Thread.onSpinWait ();
    }

  }

}
