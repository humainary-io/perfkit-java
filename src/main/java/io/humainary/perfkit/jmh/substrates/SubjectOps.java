// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Subject operations.
///
/// Measures performance of subject comparison with optimized compareTo implementations.
/// Subjects are accessed from conduits to test real-world comparison scenarios.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class SubjectOps
  implements Substrates {

  private Circuit       circuit;
  private Subject < ? > subjectA;
  private Subject < ? > subjectB;
  private Subject < ? > subjectC;

  @Setup ( Trial )
  public void setup () {

    Cortex cortex = Substrates.cortex ();

    circuit =
      cortex.circuit ();

    // Create conduits with different names to get subjects at different levels
    final var conduitA = circuit.conduit (
      cortex.name ( "conduitA" ),
      Integer.class
    );

    Conduit < Integer > conduitB = circuit.conduit (
      cortex.name ( "conduitB" ),
      Integer.class
    );

    Conduit < Integer > conduitC = circuit.conduit (
      cortex.name ( "conduitC" ),
      Integer.class
    );

    // Get subjects from conduits
    subjectA = conduitA.subject ();
    subjectB = conduitB.subject ();
    subjectC = conduitC.subject ();

  }

  ///
  /// COMPARISON: Compare subjects from different conduits.
  ///

  @Benchmark
  public int subject_compare () {

    return
      subjectA.compareTo (
        subjectB
      );

  }

  @SuppressWarnings ( "EqualsWithItself" )
  @Benchmark
  public int subject_compare_same () {

    return
      subjectA.compareTo (
        subjectA
      );

  }

  @Benchmark
  public int subject_compare_three_way () {

    return
      subjectA.compareTo ( subjectB ) +
        subjectB.compareTo ( subjectC ) +
        subjectC.compareTo ( subjectA );

  }

  @TearDown ( Trial )
  public void tearDown () {

    circuit.close ();

  }

}
