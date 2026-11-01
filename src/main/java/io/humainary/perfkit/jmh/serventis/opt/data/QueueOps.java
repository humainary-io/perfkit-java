// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.serventis.opt.data;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.serventis.opt.data.Queues;
import io.humainary.serventis.opt.data.Queues.Queue;
import io.humainary.serventis.opt.data.Queues.Sign;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static io.humainary.serventis.opt.data.Queues.Sign.ENQUEUE;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

/// Benchmark for Queues.Queue operations, representing the domain sign emit path
/// across all Serventis instrument domains.
///
/// Measures generic sign emission and named-method emission. Every sign across
/// domains travels the same underlying conduit path, so this class acts as the
/// representative emission benchmark for all instrument domains.
///
/// Domains measuring additional processing above emission (Scorecards, Cycles,
/// and Sequencers) are covered in their respective benchmark classes. Pooled
/// conduit lookup is measured once in `ConduitOps`.
///
/// ## Calibration Pair
///
/// `Queue.enqueue()` is a one-line call to `Queue.sign(ENQUEUE)` on a final class,
/// so once the JIT inlines that call the two rows do the same work. Retaining both
/// provides a runtime calibration baseline: a divergence between them in a given
/// run reflects harness or environment noise, unless the call stopped inlining —
/// so read a persistent gap as a compilation question before dismissing it.
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class QueueOps implements Substrates {

  private static final String QUEUE_NAME = "worker.queue";
  private static final int    BATCH_SIZE = 10000;

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex  cortex;
  private Circuit circuit;
  private Queue   queue;
  private Name    name;

  ///
  /// Benchmark batched ENQUEUE emissions.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_enqueue_batch (
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
      queue.enqueue ();
    }

    circuit.await ();

  }

  ///
  /// Benchmark batched generic sign emissions.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_sign_batch (
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
      queue.sign (
        ENQUEUE
      );
    }

    circuit.await ();

  }

  @Setup ( Level.Iteration )
  public void setupIteration () {

    final var tally =
      deliveries;

    deliveries.take ();

    circuit =
      cortex.circuit ();

    Conduit < Sign > conduit = circuit.conduit (
      Sign.class
    );

    conduit.subscribe (
      circuit.subscriber (
        cortex.name ( "observer" ),
        ( _, registrar ) ->
          registrar.register ( _ -> tally.increment () )
      )
    );

    final var pool = Queues.pool ( conduit );

    queue =
      pool.get (
        name
      );

    circuit.await ();

  }

  @Setup ( Level.Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    name =
      cortex.name (
        QUEUE_NAME
      );

  }

  @TearDown ( Level.Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

}
