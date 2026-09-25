// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.serventis.sdk;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.serventis.opt.pool.Resources;
import io.humainary.serventis.sdk.Scorecards;
import io.humainary.serventis.sdk.SignMap;
import io.humainary.serventis.sdk.Statuses;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static io.humainary.serventis.opt.pool.Resources.Sign.GRANT;
import static io.humainary.serventis.opt.pool.Resources.Sign.RELEASE;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for the Scorecards translation operator.
///
/// Emissions are processed on the circuit worker thread. The `*_batch`
/// benchmarks emit a batch and then `circuit.await()`, measuring tally update,
/// winner scan, confidence banding, status-signal lookup, and emission as the
/// worker drains all votes.
///
/// See [io.humainary.perfkit.jmh.serventis.opt.data.QueueOps] for the raw domain sign-emit
/// baseline this translation cost builds upon.
///


@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class ScorecardOps implements Substrates {

  private static final String SCORECARD_NAME        = "service.scorecard";
  private static final String SCORECARD_MAP_NAME    = "service.scorecard.map";
  private static final String SCORECARD_WINDOW_NAME = "service.scorecard.window";
  private static final int    BATCH_SIZE            = 10000;
  private static final int    WINDOW                = 16;

  /// The same ballot as [#ballot], pre-computed into an ordinal-indexed SignMap.

  private static final SignMap < Resources.Sign, Statuses.Sign > BALLOT =
    Resources.SIGNS.map (
      ScorecardOps::ballot
    );

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex                           cortex;
  private Circuit                          circuit;
  private Pool < Pipe < Resources.Sign > > pool;
  private Pipe < Resources.Sign >          input;
  private Pipe < Resources.Sign >          inputMap;
  private Pipe < Resources.Sign >          inputWindow;
  private Name                             name;
  private Name                             mapName;
  private Name                             windowName;

  private static Statuses.Sign ballot (
    final Resources.Sign sign
  ) {

    return
      switch ( sign ) {
        case GRANT -> Statuses.Sign.STABLE;
        case DENY, TIMEOUT -> Statuses.Sign.DEGRADED;
        default -> null;
      };

  }

  ///
  /// Benchmark the drained abstain path — the through-flow floor (scan dispatch + null ballot +
  /// filtered assess) on the worker thread, with no downstream emission.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_abstain_batch (
    final TerminalVerification verification
  ) {

    // A null ballot filters the assessment, so the abstain path is expected to
    // deliver nothing at all. Zero is the assertion, not the absence of one.
    verification.expect (
      deliveries,
      0
    );

    for (
      var i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      input.emit (
        RELEASE
      );
    }

    circuit.await ();

  }

  ///
  /// Benchmark batched scored votes, drained with `circuit.await()` — the end-to-end per-vote
  /// scoring cost (tally update + winner scan + band + status-signal emit).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_vote_batch (
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
        GRANT
      );
    }

    circuit.await ();

  }

  ///
  /// Benchmark batched scored votes with a SignMap-backed ballot, drained — head-to-head with
  /// [#emit_vote_batch] to isolate the ballot-resolution cost under the same end-to-end path.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_vote_signmap_batch (
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
      inputMap.emit (
        GRANT
      );
    }

    circuit.await ();

  }

  ///
  /// Benchmark batched windowed scoring, drained — `Scorecards.score` over a window of WINDOW signs
  /// per emission (fold + winner scan + band). Run with `-prof gc` to check per-emission allocation.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void emit_vote_window_batch (
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
      inputWindow.emit (
        GRANT
      );
    }

    circuit.await ();

  }

  @Setup ( Level.Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit ();

    final Conduit < Statuses.Signal > statuses =
      circuit.conduit ( Statuses.Signal.class );

    statuses.subscribe (
      circuit.subscriber (
        cortex.name ( "observer" ),
        ( _, registrar ) ->
          registrar.register ( _ -> deliveries.increment () )
      )
    );

    pool =
      statuses.pool (
        Scorecards.flow (
          ScorecardOps::ballot
        )
      );

    input =
      pool.get (
        name
      );

    inputMap =
      statuses.pool (
        Scorecards.flow (
          BALLOT
        )
      ).get ( mapName );

    inputWindow =
      statuses.pool (
        cortex.flow ( Resources.Sign.class )
          .window ( WINDOW )
          .map ( w -> Scorecards.score ( w, BALLOT ) )
      ).get ( windowName );

    circuit.await ();

  }

  @Setup ( Level.Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    name =
      cortex.name (
        SCORECARD_NAME
      );

    mapName =
      cortex.name (
        SCORECARD_MAP_NAME
      );

    windowName =
      cortex.name (
        SCORECARD_WINDOW_NAME
      );

  }

  @TearDown ( Level.Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

}
