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
/// Benchmark for Sink operations.
///
/// A Sink funnels each emission, enriched as a Capture, into a single endpoint pipe
/// fixed at creation — the output-closed dual of a Conduit. The capture is minted on
/// the circuit worker and forwarded to the endpoint, so these benchmarks measure the
/// per-emission capture-mint + forward cost against a plain conduit emission baseline.
/// The endpoint is a black-hole pipe so the cost reflects minting and dispatch, not
/// downstream work.
///
/// The sink, its channel, and the baseline pipe are built once per iteration in
/// [#setupIteration()], so the measured methods are pure emit loops — no per-invocation
/// factory or allocation cost is folded into the hot path.
///
/// Every batch is sized at [#BATCH_SIZE] so the one drain per invocation costs
/// under a nanosecond per operation. The former 100-operation scale points were
/// removed: a single `await()` is roughly 8 µs, so spreading it over 100
/// operations put ~80 ns of rendezvous on a ~11 ns emission and the rows
/// reported the drain rather than the sink.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class SinkOps
  implements Substrates {

  private static final String  NAME_STR   = "test";
  private static final int     VALUE      = 42;
  private static final int     BATCH_SIZE = 10000;
  private static final Integer PAYLOAD    = VALUE;

  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();

  private Cortex  cortex;
  private Circuit circuit;
  private Name    name;

  private Pipe < Object > baseline;   // black-hole conduit pipe
  private Pipe < Object > channel;    // sink channel forwarding to a black-hole endpoint

  ///
  /// BASELINE: 10000 emissions into a black-hole conduit pipe (pure emission cost).
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void baseline_emit_batch (
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
      baseline.emit (
        PAYLOAD
      );
    }

    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    circuit =
      cortex.circuit (
        name
      );

    deliveries.take ();

    // The baseline conduit dispatches to a counted receptor so that both sides
    // of the comparison deliver: without a subscriber it measured admission into
    // an inlet with no pipes, and the sink's advantage would have included the
    // dispatch the baseline never performed.
    final var baselineConduit =
      circuit.conduit ();

    baselineConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "baseline-sub" ),
        ( _, registrar ) ->
          registrar.register ( _ -> deliveries.increment () )
      )
    );

    baseline =
      baselineConduit.get (
        name
      );

    final Pipe < Capture < Object > > endpoint =
      circuit.pipe ( _ -> deliveries.increment () );

    channel =
      circuit.sink ( endpoint ).get (
        name
      );

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    name =
      cortex.name (
        NAME_STR
      );

  }

  ///
  /// HOT PATH: 10000 emissions through a sink channel, each minted as a Capture and
  /// forwarded to a black-hole endpoint. Measures capture-mint + forward cost.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void sink_emit_batch (
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
      channel.emit (
        PAYLOAD
      );
    }

    circuit.await ();

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

}
