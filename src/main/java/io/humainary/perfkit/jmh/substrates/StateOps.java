// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for State and Slot operations.
///
/// Measures performance of state transformations (adding slots, upsert, value retrieval)
/// and slot accessor methods (name, type, value). State is an immutable collection of
/// named, typed values (slots) used for metadata and context.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class StateOps
  implements Substrates {

  private static final String SLOT_NAME_1 = "slot1";
  private static final String SLOT_NAME_2 = "slot2";
  private static final String SLOT_NAME_3 = "slot3";
  private static final int    INT_VAL     = 42;
  private static final long   LONG_VAL    = 42L;
  private static final String STR_VAL     = "value";

  private Name             name1;
  private Name             name2;
  private Name             name3;
  private State            emptyState;
  private State            multiSlotState;
  private Slot < Integer > intSlot;

  @Setup ( Trial )
  public void setupTrial () {

    final Cortex cortex = Substrates.cortex ();

    name1 =
      cortex.name (
        SLOT_NAME_1
      );

    name2 =
      cortex.name (
        SLOT_NAME_2
      );

    name3 =
      cortex.name (
        SLOT_NAME_3
      );

    emptyState =
      cortex.state ();

    intSlot =
      cortex.slot (
        name1,
        INT_VAL
      );

    // Create state with three distinct slots.
    multiSlotState =
      emptyState
        .state ( intSlot )
        .state (
          cortex.slot (
            name2,
            LONG_VAL
          )
        ).state (
          cortex.slot (
            name3,
            STR_VAL
          )
        );

  }

  ///
  /// Benchmark slot name accessor.
  ///

  @Benchmark
  public Name slot_name () {

    return
      intSlot.name ();

  }

  ///
  /// Benchmark slot type accessor.
  ///

  @Benchmark
  public Class < Integer > slot_type () {

    return
      intSlot.type ();

  }

  ///
  /// Benchmark slot value accessor.
  ///

  @Benchmark
  public Integer slot_value () {

    return
      intSlot.value ();

  }

  ///
  /// Benchmark state iteration over slots.
  ///

  @Benchmark
  public int state_iterate_slots () {

    int
      count =
      0;

    for (
      final var _ : multiSlotState
    ) {
      count++;
    }

    return
      count;

  }

  ///
  /// Benchmark adding an int slot to state.
  ///

  @Benchmark
  public State state_slot_add_int () {

    return
      emptyState.state (
        name1,
        INT_VAL
      );

  }

  ///
  /// Benchmark adding a long slot to state.
  ///

  @Benchmark
  public State state_slot_add_long () {

    return
      emptyState.state (
        name2,
        LONG_VAL
      );

  }

  ///
  /// Benchmark adding a Slot object to state.
  ///

  @Benchmark
  public State state_slot_add_object () {

    return
      emptyState.state (
        intSlot
      );

  }

  ///
  /// Benchmark adding a string slot to state.
  ///

  @Benchmark
  public State state_slot_add_string () {

    return
      emptyState.state (
        name3,
        STR_VAL
      );

  }

  ///
  /// Benchmark upsert of an existing head slot with a differing value.
  /// Exercises the hot rewrite path: find() hits on the first iteration,
  /// the new head replaces it, and the chain tail is shared.
  ///

  @Benchmark
  public State state_upsert_head () {

    return
      multiSlotState.state (
        name3,
        STR_VAL + "_next"
      );

  }

  ///
  /// Benchmark upsert of a slot at the tail of the chain. Exercises the
  /// prefix-rebuild path where the matching slot is at position size-1
  /// and the walker must copy the entire prefix.
  ///

  @Benchmark
  public State state_upsert_tail () {

    return
      multiSlotState.state (
        name1,
        INT_VAL + 1
      );

  }

  ///
  /// Benchmark reading value from state (with default fallback).
  ///

  @Benchmark
  public Integer state_value_read () {

    return
      multiSlotState.value (
        intSlot
      );

  }

}
