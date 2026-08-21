// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.serventis.sdk.meta;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.serventis.opt.sync.Locks;
import io.humainary.serventis.sdk.SignSet;
import io.humainary.serventis.sdk.Statuses;
import io.humainary.serventis.sdk.meta.Sequencers;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static io.humainary.serventis.opt.sync.Locks.Sign.*;
import static io.humainary.serventis.sdk.Statuses.Sign.*;
import static io.humainary.serventis.sdk.meta.Sequencers.emit;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Sequencers sequencing operators.
///
/// Emissions are processed on the circuit worker thread. Batched benchmarks await
/// circuit completion to measure transition table lookup, state walk advance, and
/// reading emission throughput.
///
/// See `ScorecardOps` for the translation tally counterpart.
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class SequencerOps implements Substrates {

  private static final String SEQUENCER_NAME = "lock.sequencer";
  private static final int    BATCH_SIZE     = 10000;

  private static final SignSet < Locks.Sign > LOCKS =
    Locks.SIGNS;

  /// The clean lock walk — one trajectory reading per admission.

  private static final Locks.Sign[] LIFECYCLE = {
    ATTEMPT,
    GRANT,
    RELEASE
  };

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex                       cortex;
  private Circuit                      circuit;
  private Pool < Pipe < Locks.Sign > > pool;
  private Pipe < Locks.Sign >          input;
  private Name                         name;

  private static Flow < Locks.Sign, Statuses.Sign > flow () {

    return
      Sequencers.flow (
        LOCKS.map (
          sign -> switch ( sign ) {
            case ATTEMPT -> emit (
              DIVERGING,
              LOCKS.map (
                next -> switch ( next ) {
                  case GRANT -> emit (
                    CONVERGING,
                    LOCKS.map (
                      end -> switch ( end ) {
                        case RELEASE -> emit ( STABLE );
                        default -> null;
                      }
                    )
                  );
                  case DENY -> emit ( DEGRADED );
                  default -> null;
                }
              )
            );
            case RELEASE -> emit ( DEFECTIVE );
            default -> emit ( STABLE );
          }
        )
      );

  }

  ///
  /// Benchmark drained active suppression: the opening `ATTEMPT` moves the walk into the active
  /// state, where `UPGRADE` has no transition — the walk stays put and the scan filters the
  /// admission, so this measures the recognition path that emits nothing downstream. After the
  /// first invocation the walk remains active across invocations (`ATTEMPT` is also unmapped
  /// there), so the steady state is all-suppressed.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_active_suppression_batch (
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

  ///
  /// Benchmark drained idle baseline output: no walk is active, so the idle state maps each
  /// admission to the configured `STABLE` baseline.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_idle_baseline_batch (
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
        UPGRADE
      );
    }

    circuit.await ();

  }

  ///
  /// Benchmark batched lifecycle admissions, drained with `circuit.await()` — the end-to-end
  /// per-admission recognition cost (transition table lookup + walk advance + reading emission).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_lifecycle_batch (
    final TerminalVerification verification
  ) {

    // Seam of one: the first admission against a fresh machine has no prior
    // state to transition from. Later invocations inherit the previous one's
    // state and forward the whole batch.
    verification.between (
      deliveries,
      BATCH_SIZE - 1,
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

    pool =
      statuses.pool (
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
