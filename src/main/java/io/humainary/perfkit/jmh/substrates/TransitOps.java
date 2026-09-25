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
/// Benchmark for the transit phase's storage tiers.
///
/// Every other emission benchmark in the suite measures one storage tier
/// incidentally. These measure the transitions between them: the width at which
/// a transit fan-out stops fitting the provider's ring, the repeated wraps that
/// follow, and the contraction from an overflowed fan-out back to a
/// single-successor cycle inside one transit phase.
///
/// `transit` is the specification's word for the phase, which is why it names
/// this class. The provider's own word for the engine underneath it is not: a
/// benchmark suite measures an API, and an implementation's internal type names
/// are not part of one.
///
/// The widths are chosen against a 4,096-entry transit ring, which is what the
/// alpha provider uses. They are literals rather than a published constant: the
/// suite measures an API, and no API exposes a queue capacity. A provider that
/// sizes its ring differently still runs these rows; they stop bracketing its
/// boundary, and the scaling curve is what should be read instead. The widths
/// appear in the method names because the unit they report is the same at every
/// width — one transit delivery — so the rows are directly comparable and the
/// name is the only thing distinguishing them.
///
/// ## What each row prices
///
/// - `transit_fanout_1k_batch` / `_4k_` / `_8k_` — one ingress emission fanning
///   out to that many transit successors, repeated until a constant number of
///   deliveries has been made. A rise from 4k to 8k is the linked chain's
///   per-entry allocation against the ring's array store.
/// - `transit_fanout_full_ring_batch` — the widest fan-out that reaches no
///   chain at all: one slot plus a full ring. The control for the row below.
/// - `transit_fanout_over_capacity_batch` — the same shape one entry wider, so
///   exactly one entry reaches the chain. The pair isolates the first chain
///   admission from the bulk chain cost the wider rows carry; neither row alone
///   does, because the difference between them is the whole measurement.
/// - `transit_contraction_100k` — an overflowed fan-out followed, in the same
///   cascade, by a long single-successor cycle. A valve that cannot return to
///   its cheapest admission path until the phase ends pays the chain's cost for
///   all 100,000 cycle steps.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class TransitOps
  implements Substrates {

  /// Constant delivery count for the width rows, so every width reports the
  /// same unit. Each width below divides it exactly.
  private static final int    FANOUT_DELIVERIES = 65536;
  private static final int    WIDTH_1K          = 1024;
  private static final int    WIDTH_4K          = 4096;
  private static final int    WIDTH_8K          = 8192;
  /// The widest fan-out that reaches no chain: the alpha provider's queue holds
  /// one slot **beside** its 4,096-position ring, so 4,097 fills both exactly.
  private static final int    FULL_RING         = 4097;
  /// One entry past what the slot and ring hold between them, so exactly one
  /// entry reaches the chain. This is the first spill; 4,097 is not.
  private static final int    OVER_CAPACITY     = 4098;
  private static final int    SPILL_ROUNDS      = 16;
  private static final int    FULL_DELIVERIES   = FULL_RING * SPILL_ROUNDS;
  private static final int    OVER_DELIVERIES   = OVER_CAPACITY * SPILL_ROUNDS;
  /// Wide enough to fill the ring and spill the remainder to the chain.
  private static final int    CONTRACTION_WIDTH = 8192;
  private static final int    CONTRACTION_CYCLE = 100000;
  private static final int    CONTRACTION_OPS   = CONTRACTION_CYCLE + CONTRACTION_WIDTH;
  private static final Object PAYLOAD           = new Object ();

  private final Tally           deliveries = new Tally ();
  private       Cortex          cortex;
  private       Name            pipesName;
  private       Name            cyclicName;
  private       Circuit         circuit;
  private       Pipe < Object > counted;
  private       Pipe < Object > fanout1k;
  private       Pipe < Object > fanout4k;
  private       Pipe < Object > fanout8k;
  private       Pipe < Object > fullRing;
  private       Pipe < Object > overCapacity;

  @Setup ( Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit ();

    final var tally =
      deliveries;

    counted =
      circuit.pipe (
        _ -> tally.increment ()
      );

    fanout1k =
      fanout ( WIDTH_1K );

    fanout4k =
      fanout ( WIDTH_4K );

    fanout8k =
      fanout ( WIDTH_8K );

    fullRing =
      fanout ( FULL_RING );

    overCapacity =
      fanout ( OVER_CAPACITY );

    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    pipesName =
      cortex.name (
        "pipes"
      );

    cyclicName =
      cortex.name (
        "cyclic"
      );

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.await ();
    circuit.close ();

  }

  ///
  /// Contraction inside one transit phase: a fan-out that overflows the ring,
  /// then a single-successor cycle seeded from the same cascade.
  ///
  /// `CyclicOps.cyclic_emit_after_fanout_100k` checks recovery *between* ingress
  /// items, where the phase ends and every storage tier is empty before the
  /// cycle starts. Here the cycle is seeded behind the overflow, so the valve
  /// must return to its cheapest admission path while the phase is still
  /// running. The topology is rebuilt per invocation because the terminating
  /// `limit` fiber is spent once it has passed its quota.
  ///

  @Benchmark
  @OperationsPerInvocation ( CONTRACTION_OPS )
  public void transit_contraction_100k (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      CONTRACTION_OPS
    );

    final var conduit =
      circuit.conduit ();

    final var tally =
      deliveries;

    final var pool =
      conduit.pool (
        cortex.fiber ()
          .limit ( CONTRACTION_CYCLE )
          .peek ( _ -> tally.increment () )
      );

    conduit.subscribe (
      circuit.subscriber (
        pipesName,
        pool
      )
    );

    final var cycle =
      pool.get ( cyclicName );

    final var target =
      counted;

    circuit
      .pipe (
        value -> {
          for ( var i = 0; i < CONTRACTION_WIDTH; i++ ) {
            target.emit ( value );
          }
          cycle.emit ( value );
        }
      )
      .emit ( PAYLOAD );

    circuit.await ();

  }

  @Benchmark
  @OperationsPerInvocation ( FANOUT_DELIVERIES )
  public void transit_fanout_1k_batch (
    final TerminalVerification verification
  ) {

    fanoutRounds ( verification, fanout1k, WIDTH_1K );

  }

  @Benchmark
  @OperationsPerInvocation ( FANOUT_DELIVERIES )
  public void transit_fanout_4k_batch (
    final TerminalVerification verification
  ) {

    fanoutRounds ( verification, fanout4k, WIDTH_4K );

  }

  @Benchmark
  @OperationsPerInvocation ( FANOUT_DELIVERIES )
  public void transit_fanout_8k_batch (
    final TerminalVerification verification
  ) {

    fanoutRounds ( verification, fanout8k, WIDTH_8K );

  }

  ///
  /// The widest fan-out that reaches no chain: one slot plus a full ring.
  ///
  /// Read against [#transit_fanout_over_capacity_batch], which is one entry
  /// wider and therefore allocates exactly one node. The difference between the
  /// two rows is the first chain admission; neither row on its own measures it,
  /// and this one measures no chain cost whatsoever.
  ///

  @Benchmark
  @OperationsPerInvocation ( FULL_DELIVERIES )
  public void transit_fanout_full_ring_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      FULL_DELIVERIES
    );

    for (
      int round = 0;
      round < SPILL_ROUNDS;
      round++
    ) {
      fullRing.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// A fan-out one entry past what the slot and ring hold, so exactly one entry
  /// reaches the chain.
  ///
  /// The width is 4,098, not 4,097. The queue holds one slot **beside** a
  /// 4,096-position ring, so a 4,097-wide fan-out fills both exactly and
  /// allocates nothing; a row at that width prices a full ring and was named
  /// for a spill it never performed.
  ///

  @Benchmark
  @OperationsPerInvocation ( OVER_DELIVERIES )
  public void transit_fanout_over_capacity_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      OVER_DELIVERIES
    );

    for (
      int round = 0;
      round < SPILL_ROUNDS;
      round++
    ) {
      overCapacity.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  private Pipe < Object > fanout (
    final int width
  ) {

    final var target =
      counted;

    return
      circuit.pipe (
        value -> {
          for ( var i = 0; i < width; i++ ) {
            target.emit ( value );
          }
        }
      );

  }

  private void fanoutRounds (
    final TerminalVerification verification,
    final Pipe < Object > pipe,
    final int width
  ) {

    verification.expect (
      deliveries,
      FANOUT_DELIVERIES
    );

    final var rounds =
      FANOUT_DELIVERIES / width;

    for (
      int round = 0;
      round < rounds;
      round++
    ) {
      pipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

}
