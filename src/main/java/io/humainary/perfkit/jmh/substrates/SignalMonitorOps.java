// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.demo.SignalMonitor;
import io.humainary.perfkit.demo.SignalMonitor.DirectMonitor;
import io.humainary.perfkit.demo.SignalMonitor.Expected;
import io.humainary.perfkit.demo.SignalMonitor.MonitorGraph;
import io.humainary.perfkit.demo.SignalMonitor.OutputBuffer;
import io.humainary.perfkit.demo.SignalMonitor.Sample;
import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Invocation;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Matched direct and public-API execution of the signal-monitor demonstration.
///
/// Each score is normalized per original input sample. Inputs, graph assembly,
/// materialization, and reusable result storage are outside timing. The flow rows
/// include one external admission per sample, all feature and branch processing,
/// and one final `Circuit.await()`. The direct rows perform the same extraction,
/// immutable measurement construction, archiving, classification, seeded
/// duplicate suppression, and transition recording on the caller thread.
///
/// Subtracting a direct score from a flow score does not isolate dispatch: caller
/// versus circuit execution, admission, routing, and compiler treatment differ.
/// One circuit is sequential; the two branches make no multicore claim.
///
/// The workload intentionally allocates one [SignalMonitor.BlockMetrics] per 64
/// samples in every row. Run with the GC profiler to report bytes/input sample.
///
/// @since 3.2

@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class SignalMonitorOps {

  @State ( Scope.Thread )
  public abstract static class MonitorState {

    private final int sampleCount;

    Sample[]      samples;
    Expected      expected;
    OutputBuffer  directOutput;
    OutputBuffer  flowOutput;
    DirectMonitor direct;
    MonitorGraph  graph;
    Tally         deliveries;

    MonitorState (
      final int sampleCount
    ) {

      this.sampleCount =
        sampleCount;

    }

    @Setup ( Trial )
    public void setupTrial () {

      samples =
        SignalMonitor.recording ( sampleCount );

      expected =
        SignalMonitor.expected ( samples );

      final var blockCount =
        sampleCount / SignalMonitor.BLOCK_SIZE;

      deliveries =
        new Tally ();

      directOutput =
        new OutputBuffer ( blockCount );

      flowOutput =
        new OutputBuffer ( blockCount, deliveries::increment );

      direct =
        new DirectMonitor ( directOutput );

      final var cortex =
        Substrates.cortex ();

      graph =
        new MonitorGraph (
          cortex,
          SignalMonitor.recipes ( cortex ),
          flowOutput
        );

    }

    @Setup ( Invocation )
    public void setupInvocation () {

      directOutput.reset ();
      flowOutput.reset ();

      final var stale =
        deliveries.take ();

      if ( stale != 0L ) {
        throw new IllegalStateException ( "stale terminal delivery count: " + stale );
      }

    }

    @TearDown ( Invocation )
    public void verifyInvocation () {

      final var directCount =
        directOutput.measurementCount ();

      final var flowCount =
        flowOutput.measurementCount ();

      if ( directCount != 0 && flowCount == 0 ) {
        SignalMonitor.verifyOutput ( expected, directOutput );
      } else if ( flowCount != 0 && directCount == 0 ) {
        SignalMonitor.verifyOutput ( expected, flowOutput );
      } else {
        throw new IllegalStateException (
          "exactly one benchmark path must produce measurements"
        );
      }

    }

    @TearDown ( Trial )
    public void tearDownTrial () {

      graph.close ();

    }

  }

  @State ( Scope.Thread )
  public static class SmallState
    extends MonitorState {

    public SmallState () {

      super ( SignalMonitor.SAMPLE_COUNT );

    }

  }

  @State ( Scope.Thread )
  public static class LargeState
    extends MonitorState {

    public LargeState () {

      super ( SignalMonitor.LARGE_SAMPLE_COUNT );

    }

  }

  @Benchmark
  @OperationsPerInvocation ( SignalMonitor.SAMPLE_COUNT )
  public void direct_16k_batch (
    final SmallState state
  ) {

    state.direct.process ( state.samples );

  }

  @Benchmark
  @OperationsPerInvocation ( SignalMonitor.LARGE_SAMPLE_COUNT )
  public void direct_64k_batch (
    final LargeState state
  ) {

    state.direct.process ( state.samples );

  }

  @Benchmark
  @OperationsPerInvocation ( SignalMonitor.SAMPLE_COUNT )
  public void flows_fibers_16k_batch (
    final SmallState state,
    final TerminalVerification verification
  ) {

    verification.expect (
      state.deliveries,
      state.expected.deliveryCount ()
    );

    state.graph.emit ( state.samples );
    state.graph.await ();

  }

  @Benchmark
  @OperationsPerInvocation ( SignalMonitor.LARGE_SAMPLE_COUNT )
  public void flows_fibers_64k_batch (
    final LargeState state,
    final TerminalVerification verification
  ) {

    verification.expect (
      state.deliveries,
      state.expected.deliveryCount ()
    );

    state.graph.emit ( state.samples );
    state.graph.await ();

  }

}
