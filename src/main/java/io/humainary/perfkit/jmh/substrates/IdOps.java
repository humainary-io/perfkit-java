// Copyright (c) 2025 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.Trial;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for Id operations.
///
/// Measures Id retrieval from a subject and Id string conversion. There is no
/// Id-creation row: the API mints an Id only as part of creating a subject, so
/// its cost is inside whatever creates that subject rather than separable here.
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class IdOps
  implements Substrates {

  private Circuit       circuit;
  private Subject < ? > subject;
  private Id            id;

  ///
  /// Benchmark Id retrieval from subject.
  ///

  @Benchmark
  public Id id_from_subject () {

    return
      subject.id ();

  }

  ///
  /// Benchmark Id.toString() conversion.
  ///

  @Benchmark
  public String id_toString () {

    return
      id.toString ();

  }

  @Setup ( Trial )
  public void setup () {

    Cortex cortex = Substrates.cortex ();

    circuit =
      cortex.circuit ();

    Conduit < Integer > conduit = circuit.conduit (
      cortex.name ( "benchmark" ),
      Integer.class
    );

    subject =
      conduit.subject ();

    id =
      subject.id ();

  }

  @TearDown ( Trial )
  public void tearDown () {

    circuit.close ();

  }

}
