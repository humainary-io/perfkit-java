// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Cortex factory operations.
///
/// Measures the Cortex factory methods that recur at runtime: slot declaration,
/// empty-state access, and the current-context read. Cortex is the entry point
/// for creating runtime substrate instances.
///
/// Name construction is measured by `NameOps`, which owns the fuller set of
/// `cortex.name(...)` sources. The string, dotted-path, enum and iterable forms
/// were duplicated here and read the same on both sides; only `name_class` has
/// no counterpart there and stays.
///
/// Circuit and scope construction are not measured. Both are wiring-time work,
/// and circuit creation is dominated by virtual-thread startup that the harness
/// cannot hold still.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class CortexOps
  implements Substrates {

  private static final String NAME_STR = "test";
  private static final int    INT_VAL  = 42;
  private static final long   LONG_VAL = 42L;
  private static final double DBL_VAL  = 42.0;
  private static final String STR_VAL  = "value";

  private Cortex cortex;
  private Name   name;

  ///
  /// Benchmark getting current execution context.
  ///

  @Benchmark
  public Current current () {

    return
      cortex.current ();

  }

  ///
  /// Benchmark creating name from Class.
  ///

  @Benchmark
  public Name name_class () {

    return
      cortex.name (
        String.class
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
  /// Benchmark creating a slot with boolean value.
  ///

  @Benchmark
  public Slot < Boolean > slot_boolean () {

    return
      cortex.slot (
        name,
        true
      );

  }

  ///
  /// Benchmark creating a slot with double value.
  ///

  @Benchmark
  public Slot < Double > slot_double () {

    return
      cortex.slot (
        name,
        DBL_VAL
      );

  }

  ///
  /// Benchmark creating a slot with int value.
  ///

  @Benchmark
  public Slot < Integer > slot_int () {

    return
      cortex.slot (
        name,
        INT_VAL
      );

  }

  ///
  /// Benchmark creating a slot with long value.
  ///

  @Benchmark
  public Slot < Long > slot_long () {

    return
      cortex.slot (
        name,
        LONG_VAL
      );

  }

  ///
  /// Benchmark creating a slot with string value.
  ///

  @Benchmark
  public Slot < String > slot_string () {

    return
      cortex.slot (
        name,
        STR_VAL
      );

  }

  ///
  /// Benchmark creating an empty state.
  ///

  @Benchmark
  public State state_empty () {

    return
      cortex.state ();

  }

}
