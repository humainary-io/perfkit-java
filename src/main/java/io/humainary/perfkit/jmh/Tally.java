// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh;

///
/// A single-writer `long` written on a circuit context and read after a drain.
///
/// Two kinds of evidence use this. Completion evidence counts terminal deliveries
/// via `increment()`; receptor evidence records a computed result via `set(long)`
/// or accumulates one via `add(long)`, keeping the work a receptor performs
/// observable to JMH so it is not optimized away.
///
/// Both are produced by the circuit context while the caller context is still
/// inside the measured region, so the value is held off the state object and
/// padded on both sides rather than written into a field of it.
///
/// The padding is convention, not a measured optimization. A three-fork decision
/// run of the `WindowOps` consumption benchmarks against an otherwise identical
/// build writing to a plain `long` on the state object resolved no difference on
/// any row — deltas of -1.31, -0.36 and +1.34 ns against combined-error
/// thresholds of 1.40, 1.48 and 2.15, pointing in both directions. The likely
/// reason there is nothing to protect: a measured loop reads its pipe reference
/// once and the JIT hoists it, so the loop never re-reads the line the circuit
/// context is writing near. Keep the holder for uniformity across benchmark
/// classes and because it is free, not on the belief that removing it would cost
/// anything.
///
/// Only one context may write; `take()` must be called after the circuit has
/// drained, which also establishes visibility of those writes.
///
/// @since 3.0


public final class Tally {

  @SuppressWarnings ( "unused" )
  private long p1, p2, p3, p4, p5, p6, p7;

  private long count;

  @SuppressWarnings ( "unused" )
  private long q1, q2, q3, q4, q5, q6, q7;

  /// Adds `delta` to the recorded value.

  public void add (
    final long delta
  ) {

    count += delta;

  }

  /// Records one observed delivery.

  public void increment () {

    count++;

  }

  /// Replaces the recorded value with `value`.

  public void set (
    final long value
  ) {

    count = value;

  }

  /// Returns the recorded deliveries and resets the counter to zero.

  public long take () {

    final var value =
      count;

    count =
      0L;

    return
      value;

  }

}
