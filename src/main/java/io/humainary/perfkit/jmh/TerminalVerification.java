// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh;

import io.humainary.substrates.api.Substrates.Circuit;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

///
/// Invocation-scoped completion evidence for asynchronous benchmarks.
///
/// A benchmark declares this state as a parameter and states its expectation at
/// the top of its body; the count is checked, and any post-timer drain performed,
/// in invocation teardown. Because JMH applies a state's fixtures only to the
/// benchmarks that declare it, benchmarks in the same class that need no
/// completion evidence keep a clean per-invocation path. Declaring the same
/// fixtures on the benchmark class instead charges every method in that class for
/// them, which is fatal for the unbatched nanosecond-scale benchmarks.
///
/// @since 3.0

@State ( Scope.Thread )
public class TerminalVerification {

  private Tally   tally;
  private Circuit drain;
  private long    least;
  private long    most;

  /// Verifies a delivery count within `[least, most]` after the body has drained.
  ///
  /// An exact count is the rule; a range is for the operators that cannot have
  /// one — a probabilistic pass such as `chance`, or a processing-time interval
  /// such as `every(Duration)` and `heartbeat`, whose survivor count depends on
  /// how long the iteration ran rather than on what the benchmark emitted. A
  /// range still fails a silently dropped path, which is what the check is for.

  public void between (
    final Tally tally,
    final long least,
    final long most
  ) {

    this.tally =
      tally;

    this.least =
      least;

    this.most =
      most;

    drain =
      null;

  }

  /// Verifies a delivery count within `[least, most]` after draining `circuit`
  /// outside the timer.

  public void between (
    final Tally tally,
    final long least,
    final long most,
    final Circuit circuit
  ) {

    between (
      tally,
      least,
      most
    );

    drain =
      circuit;

  }

  /// Drains `circuit` outside the timer without counting terminal deliveries.

  public void drainAfter (
    final Circuit circuit
  ) {

    tally =
      null;

    least =
      0L;

    most =
      0L;

    drain =
      circuit;

  }

  /// Verifies `expected` deliveries after draining `circuit` outside the timer.

  public void expect (
    final Tally tally,
    final long expected,
    final Circuit circuit
  ) {

    between (
      tally,
      expected,
      expected,
      circuit
    );

  }

  /// Verifies exactly `expected` deliveries after the benchmark body has drained.

  public void expect (
    final Tally tally,
    final long expected
  ) {

    between (
      tally,
      expected,
      expected
    );

  }

  @TearDown ( Level.Invocation )
  public void verify () {

    if ( drain != null ) {
      drain.await ();
    }

    if ( tally == null ) {
      return;
    }

    final var observed =
      tally.take ();

    if ( observed < least || observed > most ) {
      throw new IllegalStateException (
        "benchmark completed " + observed + " terminal deliveries; expected " +
          ( least == most
            ? String.valueOf ( least )
            : least + ".." + most )
      );
    }

  }

}
