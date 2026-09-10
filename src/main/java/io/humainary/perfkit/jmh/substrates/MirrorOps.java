// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.function.Function.identity;
import static org.openjdk.jmh.annotations.Level.Iteration;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for source-to-conduit mirroring — the pool-backed subscriber
/// composition that replaced the former `Tap` type:
///
/// ```java
/// Conduit<T> mirror = circuit.conduit(type);
/// Subscription bridge =
///   source.subscribe(circuit.subscriber(name, mirror.pool(flow)));
/// ```
///
/// Measures performance of:
/// 1. Emission through mirror - transformation overhead vs baseline conduit
/// 2. Multiple mirrors - fan-out through multiple transformations
///
/// Emission benchmarks declare a [TerminalVerification] parameter so the
/// terminal receptor's delivery count is read back rather than optimized away.
///
/// Bridge construction and teardown are not measured. Both were dominated by
/// circuit lifecycle rather than by any mirror work: the close benchmark spent
/// its whole score on the caller-to-worker rendezvous, and the creation
/// benchmarks needed invocation-scoped circuit fixtures that `-prof gc` could
/// not exclude, so their allocation was never attributable. A bridge is built
/// once at wiring time; what recurs is the emission path measured here.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class MirrorOps
  implements Substrates {

  private static final String                    NAME_STR                    = "test";
  private static final int                       VALUE                       = 42;
  private static final int                       BATCH_SIZE                  = 10000;
  private static final Integer                   PAYLOAD                     = VALUE;
  // Terminal deliveries counted by the circuit context, held off this state object.
  private final        Tally                     deliveries                  = new Tally ();
  private final        Receptor < Integer >      integerVerificationReceptor =
    _ -> deliveries.increment ();
  private final        Receptor < String >       stringVerificationReceptor  =
    _ -> deliveries.increment ();
  private              Cortex                    cortex;
  private              Name                      name;
  private              Circuit                   circuit;
  private              Pipe < Integer >          baselinePipe;
  private              Pipe < Integer >          identityMirrorPipe;
  private              Pipe < Integer >          stringMirrorPipe;
  private              Pipe < Integer >          multiMirrorPipe;
  private              Flow < Integer, Integer > identityFlow;
  private              Flow < Integer, String >  stringFlow;

  //
  // BASELINE EMISSION BENCHMARKS
  //

  ///
  /// Baseline emission through conduit without mirror.
  /// Provides comparison baseline for mirror overhead measurement. The score
  /// is normalized per source admission and includes a complete circuit drain.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void baseline_emit_batch (
    final TerminalVerification verification
  ) {

    verification.expect ( deliveries, BATCH_SIZE );

    for ( int i = 0; i < BATCH_SIZE; i++ ) {
      baselinePipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  //
  // MIRRORED EMISSION BENCHMARKS
  //

  ///
  /// Emission through identity mirror (minimal transformation).
  /// Measures mirror routing overhead without transformation cost, normalized
  /// per source admission after a complete circuit drain.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void mirror_emit_identity_batch (
    final TerminalVerification verification
  ) {

    verification.expect ( deliveries, BATCH_SIZE );

    for ( int i = 0; i < BATCH_SIZE; i++ ) {
      identityMirrorPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Emission with multiple mirrors on same conduit.
  /// Measures fan-out overhead through 2 mirrors (2x terminal work). The score
  /// remains normalized per source admission; teardown verifies two terminal
  /// deliveries for every admission.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void mirror_emit_multi_batch (
    final TerminalVerification verification
  ) {

    verification.expect ( deliveries, BATCH_SIZE * 2L );

    for ( int i = 0; i < BATCH_SIZE; i++ ) {
      multiMirrorPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  ///
  /// Emission through String transformation mirror.
  /// Measures mirror overhead with allocating transformation, normalized per
  /// source admission after a complete circuit drain.
  ///

  @Benchmark
  @OperationsPerInvocation ( BATCH_SIZE )
  public void mirror_emit_string_batch (
    final TerminalVerification verification
  ) {

    verification.expect ( deliveries, BATCH_SIZE );

    for ( int i = 0; i < BATCH_SIZE; i++ ) {
      stringMirrorPipe.emit ( PAYLOAD );
    }

    circuit.await ();

  }

  @Setup ( Iteration )
  public void setupIteration () {

    deliveries.take ();

    circuit =
      cortex.circuit (
        name
      );

    // ─────────────────────────────────────────────────────────────────
    // BASELINE: conduit → 1 direct subscriber (no mirror in path)
    // This measures pure conduit emission overhead
    // ─────────────────────────────────────────────────────────────────
    final var baselineConduit = circuit.conduit (
      Integer.class
    );

    baselineConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "baseline-sub" ),
        ( _, registrar ) ->
          registrar.register ( integerVerificationReceptor )
      )
    );

    baselinePipe =
      baselineConduit.get ( name );

    // ─────────────────────────────────────────────────────────────────
    // IDENTITY MIRROR: conduit → bridged mirror → 1 subscriber
    // Measures mirror routing overhead (no transformation cost)
    // ─────────────────────────────────────────────────────────────────
    final var identityConduit = circuit.conduit (
      Integer.class
    );

    final var identityMirror = circuit.conduit (
      Integer.class
    );

    identityConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "identity-bridge" ),
        identityMirror.pool ( identityFlow )
      )
    );

    identityMirror.subscribe (
      circuit.subscriber (
        cortex.name ( "identity-sub" ),
        ( _, registrar ) ->
          registrar.register ( integerVerificationReceptor )
      )
    );

    identityMirrorPipe =
      identityConduit.get ( name );

    // ─────────────────────────────────────────────────────────────────
    // STRING MIRROR: conduit → bridged mirror (transform) → 1 subscriber
    // Measures mirror overhead with allocating transformation
    // ─────────────────────────────────────────────────────────────────
    final var stringConduit = circuit.conduit (
      Integer.class
    );

    final var stringMirror = circuit.conduit (
      String.class
    );

    stringConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "string-bridge" ),
        stringMirror.pool ( stringFlow )
      )
    );

    stringMirror.subscribe (
      circuit.subscriber (
        cortex.name ( "string-sub" ),
        ( _, registrar ) ->
          registrar.register ( stringVerificationReceptor )
      )
    );

    stringMirrorPipe =
      stringConduit.get ( name );

    // ─────────────────────────────────────────────────────────────────
    // MULTI-MIRROR: conduit → 2 bridged mirrors → 2 subscribers
    // Measures fan-out overhead (2x baseline work expected)
    // ─────────────────────────────────────────────────────────────────
    final var multiConduit = circuit.conduit (
      Integer.class
    );

    final var multiMirror1 = circuit.conduit (
      Integer.class
    );

    multiConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "multi-bridge-1" ),
        multiMirror1.pool ( identityFlow )
      )
    );

    multiMirror1.subscribe (
      circuit.subscriber (
        cortex.name ( "multi-sub-1" ),
        ( _, registrar ) ->
          registrar.register ( integerVerificationReceptor )
      )
    );

    final var multiMirror2 = circuit.conduit (
      Integer.class
    );

    multiConduit.subscribe (
      circuit.subscriber (
        cortex.name ( "multi-bridge-2" ),
        multiMirror2.pool ( identityFlow )
      )
    );

    multiMirror2.subscribe (
      circuit.subscriber (
        cortex.name ( "multi-sub-2" ),
        ( _, registrar ) ->
          registrar.register ( integerVerificationReceptor )
      )
    );

    multiMirrorPipe =
      multiConduit.get ( name );

    // Warm up - ensure all subscriptions are registered
    circuit.await ();

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    name =
      cortex.name (
        NAME_STR
      );

    identityFlow =
      cortex.flow ( Integer.class ).map ( identity () );

    stringFlow =
      cortex.flow ( Integer.class ).map ( Object::toString );

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.close ();

  }

}
