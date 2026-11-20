// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.CompilerControl.Mode.DONT_INLINE;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Scope operations.
///
/// Scope is the one lifecycle type the suite measures. The general exclusion of
/// lifecycle from this suite rests on circuit creation being dominated by
/// virtual-thread startup and scheduler effects the harness cannot stabilize
/// (see BENCHMARKS.md). None of that applies here: a scope owns no thread, and
/// creating and closing one is allocation and pointer work whose scores repeat
/// across runs.
///
/// What is measured is the cost of closing a scope, which is a cascade — the
/// scope's registered closures, then everything nested below it. That cascade
/// is the part that changes when the traversal changes, and the part where a
/// regression is otherwise invisible: it lies off the emission path, so no
/// other benchmark in the suite moves when it gets slower.
///
/// The shapes are chosen to separate the costs a cascade can have:
///
/// - **leaf** — the common case. A scope with no children should need no
///   traversal state at all, so this row should not allocate beyond the scope
///   itself.
/// - **children** — one level of nesting, where the descent begins to cost.
/// - **nested** — a chain, where the cost is the depth of the descent rather
///   than the breadth.
/// - **register** — a scope holding many resources, where registering one
///   first asks whether the scope already holds it.
/// - **consume** — closures leaving a scope oldest first, each from behind
///   every member still in it.
/// - **reregister** — one resource offered again behind a growing run of child
///   scopes, which are members too.
/// - **subscriptions** — subscriptions to one conduit closed with their scope,
///   newest first, the reverse of the order the conduit holds them in.
///
/// Create and close are measured together. Closing alone would need
/// per-invocation fixture setup, which JMH cannot time reliably at this scale;
/// the paired operation is the honest unit, and the leaf row doubles as the
/// baseline the other two are read against.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class ScopeOps
  implements Substrates {

  private static final int BREADTH = 4;
  private static final int DEPTH   = 8;

  private Name name;

  /// The enclosing scope every measured scope is created within, so that the
  /// rows measure a nested create and close rather than a root one.
  private Scope root;

  ///
  /// CHILDREN: create a scope, nest [#BREADTH] children directly under it,
  /// and close the parent so the cascade reaches all of them.
  ///

  @Benchmark
  public void scope_create_close_children () {

    final var scope =
      root.scope ( name );

    for (
      var i = 0;
      i < BREADTH;
      i++
    ) {
      scope.scope ( name );
    }

    scope.close ();

  }

  ///
  /// LEAF: create a scope with no children and close it.
  ///
  /// The baseline shape, and the one that should carry no traversal cost.
  ///

  @Benchmark
  public void scope_create_close_leaf () {

    root.scope ( name )
      .close ();

  }

  ///
  /// NESTED: create a chain [#DEPTH] deep and close the top, so the cascade
  /// descends rather than fans out.
  ///

  @Benchmark
  public void scope_create_close_nested () {

    final var scope =
      root.scope ( name );

    var current = scope;

    for (
      var i = 0;
      i < DEPTH;
      i++
    ) {
      current =
        current.scope ( name );
    }

    scope.close ();

  }

  ///
  /// CONTROL: create a scope and close it without nesting anything, creating it
  /// behind a call that is never inlined so that creation cannot be folded away.
  ///
  /// Paired with the leaf row at the same scale. Its score is diagnostic and is
  /// never subtracted from the rows above.
  ///

  @Benchmark
  public void scope_create_close_observed () {

    create ()
      .close ();

  }

  /// The control row's scope. A provider whose creation and close the compiler
  /// sees together can have both removed, which a Blackhole does not prevent:
  /// consuming the scope keeps the scope but not its link into [#root].
  @CompilerControl ( DONT_INLINE )
  private Scope create () {

    return
      root.scope ( name );

  }

  ///
  /// REGISTER: create a scope, register [Registrations#count] resources with
  /// it, and close it.
  ///
  /// A scope registers a resource once however often it is offered, so each
  /// registration first looks for an earlier one; how this row grows with
  /// `count` is the cost of that lookup. The resources were closed at setup,
  /// and closing a closed resource does nothing, so the close is the scope's
  /// walk alone.
  ///
  @Benchmark
  public void scope_register_close (
    final Registrations registrations
  ) {

    final var scope =
      root.scope ( name );

    for (
      final var resource
      : registrations.resources
    ) {
      scope.register ( resource );
    }

    scope.close ();

  }

  ///
  /// CONSUME: create a scope, take a closure for each of [Registrations#count]
  /// resources, consume them oldest first, and close the scope.
  ///
  /// Each consume takes its closure out of the scope, and oldest first every
  /// one sits behind all the closures not yet consumed; how this row grows with
  /// `count` is the cost of that removal. Consuming closes the resource, which
  /// was closed at setup, so the close does nothing.
  ///
  @Benchmark
  public void scope_consume_oldest_first (
    final Registrations registrations
  ) {

    final var scope =
      root.scope ( name );

    final var closures =
      registrations.closures;

    for (
      var i = 0;
      i < closures.length;
      i++
    ) {
      closures[i] =
        scope.closure ( registrations.resources[i] );
    }

    for (
      final var closure
      : closures
    ) {
      closure.consume ( _ -> { } );
    }

    scope.close ();

  }

  ///
  /// REREGISTER: register one resource, then [Registrations#count] times create
  /// a child scope and register the resource again, and close the scope.
  ///
  /// Each repeat finds the resource behind every child scope made so far; how
  /// this row grows with `count` is whether that search stays linear once it
  /// runs long.
  ///
  @Benchmark
  public void scope_reregister_behind_children (
    final Registrations registrations
  ) {

    final var scope =
      root.scope ( name );

    final var resource =
      registrations.resources[0];

    scope.register ( resource );

    for (
      var i = 0;
      i < registrations.count;
      i++
    ) {
      scope.scope ( name );
      scope.register ( resource );
    }

    scope.close ();

  }

  ///
  /// SUBSCRIPTIONS: subscribe each of [Registrations#count] subscribers to one
  /// conduit within a scope, close the scope, and wait for the circuit.
  ///
  /// The scope closes its subscriptions newest first, and each leaves both the
  /// conduit's roster, which holds them oldest first, and its subscriber's; how
  /// this row grows with `count` is the cost of those removals. The wait is
  /// included because unsubscribing completes on the circuit's worker.
  ///
  @Benchmark
  public void scope_close_subscriptions (
    final Registrations registrations
  ) {

    final var scope =
      root.scope ( name );

    final var conduit =
      registrations.conduit;

    for (
      final var subscriber
      : registrations.subscribers
    ) {
      scope.register (
        conduit.subscribe ( subscriber )
      );
    }

    scope.close ();

    registrations.circuit.await ();

  }

  ///
  /// IDEMPOTENT: close an already-closed scope.
  ///
  /// The repeat close must short-circuit before any traversal; this row is flat
  /// against the leaf row by exactly the cost of that second call.
  ///

  @Benchmark
  public void scope_create_close_twice () {

    final var scope =
      root.scope ( name );

    scope.close ();
    scope.close ();

  }

  @Setup ( Trial )
  public void setup () {

    final var cortex =
      Substrates.cortex ();

    name =
      cortex.name ( "scope" );

    root =
      cortex.scope (
        cortex.name ( "root" )
      );

  }

  @TearDown ( Trial )
  public void tearDown () {

    root.close ();

  }

  ///
  /// The resources behind `scope_register_close` and
  /// `scope_consume_oldest_first`: [#count] subscribers on one circuit, each
  /// closed once here so the measured close finds it closed, and the array the
  /// consume row takes its closures into.
  ///

  @org.openjdk.jmh.annotations.State ( org.openjdk.jmh.annotations.Scope.Benchmark )
  public static class Registrations {

    @Param ( {"4", "64", "1024"} )
    public int count;

    private Circuit                    circuit;
    private Subscriber < ? >[]         resources;
    private Closure < ? >[]            closures;
    private Conduit < Object >         conduit;
    private Subscriber < Object >[]    subscribers;

    @Setup ( Trial )
    public void setup () {

      final var cortex =
        Substrates.cortex ();

      final var name =
        cortex.name ( "registered" );

      circuit =
        cortex.circuit ();

      resources =
        new Subscriber < ? >[count];

      closures =
        new Closure < ? >[count];

      for (
        var i = 0;
        i < count;
        i++
      ) {

        resources[i] =
          circuit.< Object >subscriber (
            name,
            ( _, _ ) -> { }
          );

        resources[i].close ();

      }

      conduit =
        circuit.conduit ();

      @SuppressWarnings ( "unchecked" )
      final var created =
        (Subscriber < Object >[]) new Subscriber < ? >[count];

      subscribers =
        created;

      for (
        var i = 0;
        i < count;
        i++
      ) {
        subscribers[i] =
          circuit.subscriber (
            name,
            ( _, _ ) -> { }
          );
      }

      circuit.await ();

    }

    @TearDown ( Trial )
    public void tearDown () {

      circuit.close ();

    }

  }

}
