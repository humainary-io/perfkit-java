// Copyright (c) 2025 William David Louth

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
/// Benchmark comparing hierarchical emission propagation mechanisms.
///
/// Two approaches for dispatching an emission upward through a name hierarchy:
///
/// 1. **STEM routing**: Built-in parent-chain walk in `Inlet.receive()`.
///    The circuit thread traverses `Inlet.parent` references resolved lazily
///    from hierarchical name prefixes.
///
/// 2. **Subscriber-cached parent percept**: A subscriber registers the parent
///    pipe as an outlet at each level. Emissions dispatch through the regular
///    outlet list; no parent-chain walk is needed at emission time.
///
/// Both achieve the same observable result — a leaf emission reaches receptors
/// registered at every ancestor level — but take different code paths. That
/// equivalence is enforced rather than assumed: both verify `BATCH_SIZE * depth`
/// terminal deliveries, so a run in which one of them stops propagating fails
/// instead of quietly returning the other's baseline.
///
/// Each benchmark emits to a leaf pipe at the configured `depth` and awaits
/// full processing. Receptors are no-op to isolate propagation cost from
/// observer cost.
///
/// ## Normalization
///
/// All three rows are `@OperationsPerInvocation(BATCH_SIZE)` — normalized per
/// **source admission**, not per terminal delivery, the same convention the
/// fan-out benchmarks use. At `depth=10` a stem or subscriber row therefore
/// covers ten receptor invocations per counted operation, and dividing its score
/// by `depth` to recover a per-delivery cost is not a comparison this class
/// supports. `baseline_emit_batch` stays at one delivery per admission by
/// construction and is the floor the other two are read against.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class StemOps
  implements Substrates {

  private static final int VALUE      = 42;
  private static final int BATCH_SIZE = 10000;
  /// Terminal deliveries counted by the circuit context, held off this state object.
  private final Tally deliveries = new Tally ();
  @Param ( {"1", "3", "5", "10"} )
  private int depth;
  private Cortex  cortex;
  private Circuit circuit;

  // Leaf pipes for each mechanism
  private Pipe < Integer > stemLeaf;
  private Pipe < Integer > subscriberLeaf;
  private Pipe < Integer > baselineLeaf;

  //
  // BENCHMARKS
  //

  /// Baseline: PIPE routing, single subscriber, no parent propagation.
  /// Establishes the per-emission cost floor at the configured depth.

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
      baselineLeaf.emit ( VALUE );
    }
    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit ();

    // Build a hierarchical leaf name at the configured depth.
    // depth=1 → "leaf" (no parents)
    // depth=3 → "d0.d1.leaf" (2 parents)
    // depth=N → "d0.d1. ... .d(N-2).leaf" (N-1 parents)

    final var leafName = buildLeafName ();

    // --- Baseline: PIPE routing, no parent propagation ---

    final var baselineConduit =
      circuit.conduit (
        cortex.name ( "baseline" ),
        Integer.class
      );

    baselineConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "observer.baseline" ),
        ( _, registrar ) ->
          registrar.register (
            _ -> deliveries.increment ()
          )
      )
    );

    baselineLeaf =
      baselineConduit.get ( leafName );

    // --- STEM routing: built-in parent chain ---

    // Routing.STEM is what makes this the stem benchmark. The two-argument
    // overload is Routing.PIPE, under which the parent chain is never resolved
    // and a leaf emission reaches one receptor at every depth — the row then
    // measures its own baseline. See BENCHMARKS.md, "A name that outruns the
    // fixture".

    final var stemConduit =
      circuit.conduit (
        cortex.name ( "stem" ),
        Integer.class,
        Routing.STEM
      );

    stemConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "observer.stem" ),
        ( _, registrar ) ->
          registrar.register (
            _ -> deliveries.increment ()
          )
      )
    );

    stemLeaf =
      stemConduit.get ( leafName );

    // --- Subscriber-cached parent percept: PIPE routing + explicit parent wiring ---

    final var subscriberConduit =
      circuit.conduit (
        cortex.name ( "subscriber" ),
        Integer.class
      );

    subscriberConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "observer.subscriber" ),
        ( subject, registrar ) -> {
          registrar.register (
            _ -> deliveries.increment ()
          );
          subject.name ().enclosure ().ifPresent (
            prefix -> registrar.register (
              subscriberConduit.get ( prefix )
            )
          );
        }
      )
    );

    subscriberLeaf =
      subscriberConduit.get ( leafName );

    // Warm up: force subscriber callbacks and any lazy resolution.
    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

  }

  //
  // SETUP / TEARDOWN
  //

  /// STEM routing: built-in parent-chain walk per emission.

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void stem_emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      (long) BATCH_SIZE * depth
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      stemLeaf.emit ( VALUE );
    }
    circuit.await ();

  }

  /// Subscriber-cached parent percept: parent emission dispatched via outlet list.

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void subscriber_emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect (
      deliveries,
      (long) BATCH_SIZE * depth
    );

    for (
      int i = 0;
      i < BATCH_SIZE;
      i++
    ) {
      subscriberLeaf.emit ( VALUE );
    }
    circuit.await ();

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }


  //
  // HELPERS
  //

  private Name buildLeafName () {

    if ( depth == 1 ) {
      return cortex.name ( "leaf" );
    }

    // Build a hierarchical name: d0.d1. ... .d(N-2).leaf
    var name = cortex.name ( "d0" );

    for ( int i = 1; i < depth - 1; i++ ) {
      name = name.name ( "d" + i );
    }

    return name.name ( "leaf" );

  }

}
