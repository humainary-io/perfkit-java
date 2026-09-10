// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.serventis.sdk.meta;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.serventis.opt.sync.Locks;
import io.humainary.serventis.sdk.Statuses;
import io.humainary.serventis.sdk.meta.Sequencers;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static io.humainary.serventis.opt.sync.Locks.Sign.*;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for the universal **bracket sequencer** — `Sequencers.flow(OPERATION, STATUS)` —
/// which derives a status trajectory directly from an operations classification and its
/// canonical status map.
///
/// Batched emissions await circuit completion to measure end-to-end classification, bracket advance,
/// and status emission throughput.
///
/// See [SequencerOps] for the explicit state machine counterpart.
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class BracketSequencerOps implements Substrates {

  private static final String SEQUENCER_NAME = "lock.bracket";
  private static final int    BATCH_SIZE     = 10000;

  /// The clean lock span — one trajectory reading per admission.

  private static final Locks.Sign[] LIFECYCLE = {
    ATTEMPT,
    GRANT,
    RELEASE
  };

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex              cortex;
  private Circuit             circuit;
  private Pipe < Locks.Sign > input;
  private Name                name;

  private static Flow < Locks.Sign, Statuses.Sign > flow () {

    return
      Sequencers.flow (
        Locks.OPERATION,
        Locks.STATUS
      );

  }

  ///
  /// Benchmark batched lifecycle admissions, drained with `circuit.await()` — the end-to-end
  /// per-admission cost (two classification lookups + bracket advance + reading emission).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_lifecycle_batch (
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
      input.emit (
        LIFECYCLE[i % 3]
      );
    }

    circuit.await ();

  }

  ///
  /// Benchmark drained mid-span suppression: the opening `ATTEMPT` opens the span, then `UPGRADE`
  /// advances it with no verdict — the bracket stays open and the scan filters the admission, so this
  /// measures the recognition path that emits nothing downstream.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_suppression_batch (
    final TerminalVerification verification
  ) {

    // The sequencer emits on transition only. The first admission of an
    // iteration drives the entry transition and is forwarded; every later one
    // finds the machine already in the suppressing state and is dropped, so a
    // fresh circuit yields one delivery and subsequent invocations yield none.
    verification.between (
      deliveries,
      0,
      1
    );

    input.emit (
      ATTEMPT
    );

    for (
      var i = 1;
      i < BATCH_SIZE;
      i++
    ) {
      input.emit (
        UPGRADE
      );
    }

    circuit.await ();

  }

  @Setup ( Level.Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit ();

    final Conduit < Statuses.Sign > statuses =
      circuit.conduit ( Statuses.Sign.class );

    statuses.subscribe (
      circuit.subscriber (
        cortex.name ( "observer" ),
        ( _, registrar ) ->
          registrar.register ( _ -> deliveries.increment () )
      )
    );

    final var pool = statuses.pool (
      flow ()
    );

    input =
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
        SEQUENCER_NAME
      );

  }

  @TearDown ( Level.Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

}
