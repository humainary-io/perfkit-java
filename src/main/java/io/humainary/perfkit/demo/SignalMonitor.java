// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.demo;

import io.humainary.substrates.api.Substrates;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Flow;
import io.humainary.substrates.api.Substrates.Pipe;

import java.util.Arrays;

///
/// A block-energy and peak monitor built from `Flow.scan`, `Flow.map`, and
/// `Fiber.diff`.
///
/// The input is a deterministic recording. A stateful feature flow reduces each
/// 64-sample block to one immutable measurement, then two independently
/// materialized classifier branches suppress stable classifications and retain
/// only changes.
///
/// Run with `--verify` for silent-on-success executable correctness checking.
/// Run without arguments for the verification plus a small demonstration.
///
/// @since 3.2

public final class SignalMonitor {

  public static final int BLOCK_SIZE         = 64;
  public static final int SAMPLE_COUNT       = 16_384;
  public static final int LARGE_SAMPLE_COUNT = 65_536;

  public static final long ACTIVITY_THRESHOLD =
    (long) BLOCK_SIZE * 4096 * 4096;

  public static final int PEAK_THRESHOLD = 12_000;

  private static final int  MOTIF_BLOCKS   = 32;
  private static final long RECORDING_SEED = 0x6A09E667F3BCC909L;

  private SignalMonitor () { }

  private static Sample[] block (
    final int blockIndex,
    final java.util.function.IntUnaryOperator amplitude
  ) {

    final var samples =
      new Sample[BLOCK_SIZE];

    for ( int offset = 0; offset < BLOCK_SIZE; offset++ ) {
      samples[offset] =
        new Sample (
          blockIndex * BLOCK_SIZE + offset,
          amplitude.applyAsInt ( offset )
        );
    }

    return
      samples;

  }

  private static Sample[] boundaryCases () {

    return
      concatenate (
        block ( 0, _ -> 4096 ),
        block ( 1, _ -> 4095 ),
        block ( 2, _ -> 4097 ),
        block ( 3, i -> i == 0 ? 11_999 : 0 ),
        block ( 4, i -> i == 0 ? -12_000 : 0 ),
        block ( 5, i -> i == 0 ? 12_001 : 0 ),
        block ( 6, i -> i == 0 ? Short.MIN_VALUE : 0 ),
        block ( 7, i -> i == 0 ? Short.MAX_VALUE : 0 ),
        block ( 8, _ -> 0 )
      );

  }

  public static Activity classifyActivity (
    final long energy
  ) {

    return
      energy >= ACTIVITY_THRESHOLD
      ? Activity.ACTIVE
      : Activity.QUIET;

  }

  public static Excursion classifyExcursion (
    final int peak
  ) {

    return
      peak >= PEAK_THRESHOLD
      ? Excursion.PEAK
      : Excursion.CLEAR;

  }

  private static Sample[] concatenate (
    final Sample[]... blocks
  ) {

    var length =
      0;

    for ( final var block : blocks ) {
      length += block.length;
    }

    final var samples =
      new Sample[length];

    var offset =
      0;

    for ( final var block : blocks ) {
      System.arraycopy ( block, 0, samples, offset, block.length );
      offset += block.length;
    }

    return
      samples;

  }

  private static void demonstrate () {

    final var samples =
      recording ( SAMPLE_COUNT );

    final var expected =
      expected ( samples );

    final var cortex =
      Substrates.cortex ();

    final var output =
      new OutputBuffer ( expected.measurements ().length );

    try ( final var graph = new MonitorGraph ( cortex, recipes ( cortex ), output ) ) {

      graph.emit ( samples );
      graph.await ();
      verifyOutput ( expected, output );

    }

    System.out.println ( "Samples become 64-sample energy/peak measurements; two map + seeded diff branches retain meaningful changes." );
    System.out.println ( "Verified: " + samples.length + " samples, " + output.measurementCount () +
      " measurements, " + output.activityCount () + " activity transitions, " +
      output.excursionCount () + " excursion transitions." );

    System.out.println ( "Selected measurements:" );

    final int[] selected = {0, 8, 16, 20, 21, 24, 28, 31};

    for ( final var index : selected ) {
      System.out.println ( "  " + output.measurement ( index ) );
    }

    System.out.println ( "Activity transitions: " + Arrays.toString ( expected.activityTransitions () ) );
    System.out.println ( "Excursion transitions: " + Arrays.toString ( expected.excursionTransitions () ) );

  }

  /// Straightforward, group-at-a-time oracle independent of [BlockState].

  public static Expected expected (
    final Sample[] samples
  ) {

    if ( samples.length % BLOCK_SIZE != 0 ) {
      throw new IllegalArgumentException ( "recording must contain complete blocks" );
    }

    final var blockCount =
      samples.length / BLOCK_SIZE;

    final var measurements =
      new BlockMetrics[blockCount];

    final var activities =
      new ActivityEvent[blockCount];

    final var excursions =
      new ExcursionEvent[blockCount];

    var activityCount =
      0;

    var excursionCount =
      0;

    var previousActivity =
      Activity.QUIET;

    var previousExcursion =
      Excursion.CLEAR;

    for ( int block = 0; block < blockCount; block++ ) {

      final var start =
        block * BLOCK_SIZE;

      long energy =
        0L;

      var peak =
        0;

      for ( int offset = 0; offset < BLOCK_SIZE; offset++ ) {

        final var amplitude =
          samples[start + offset].amplitude ();

        energy +=
          (long) amplitude * amplitude;

        peak =
          Math.max ( peak, Math.abs ( amplitude ) );

      }

      final var blockIndex =
        samples[start + BLOCK_SIZE - 1].index () / BLOCK_SIZE;

      final var measurement =
        new BlockMetrics ( blockIndex, energy, peak );

      measurements[block] =
        measurement;

      final var activity =
        measurement.activity ();

      if ( activity != previousActivity ) {
        activities[activityCount++] = new ActivityEvent ( blockIndex, activity );
        previousActivity = activity;
      }

      final var excursion =
        measurement.excursion ();

      if ( excursion != previousExcursion ) {
        excursions[excursionCount++] = new ExcursionEvent ( blockIndex, excursion );
        previousExcursion = excursion;
      }

    }

    return
      new Expected (
        measurements,
        Arrays.copyOf ( activities, activityCount ),
        Arrays.copyOf ( excursions, excursionCount )
      );

  }

  static void main (
    final String[] arguments
  ) {

    final var verifyOnly =
      arguments.length == 1 && "--verify".equals ( arguments[0] );

    if ( arguments.length > 1 || arguments.length == 1 && !verifyOnly ) {
      throw new IllegalArgumentException ( "usage: SignalMonitor [--verify]" );
    }

    verify ();

    if ( verifyOnly ) {
      System.out.println ( "Signal monitor verification passed." );
      return;
    }

    demonstrate ();

  }

  public static Recipes recipes (
    final Cortex cortex
  ) {

    final Flow < Sample, BlockMetrics > features =
      cortex.flow ( Sample.class )
        .scan (
          BlockState::new,
          BlockState::add,
          BlockState::snapshotIfComplete
        );

    final Flow < BlockMetrics, Activity > activity =
      cortex.flow ( BlockMetrics.class )
        .map ( BlockMetrics::activity )
        .fiber (
          cortex.fiber ( Activity.class ).diff ( Activity.QUIET )
        );

    final Flow < BlockMetrics, Excursion > excursion =
      cortex.flow ( BlockMetrics.class )
        .map ( BlockMetrics::excursion )
        .fiber (
          cortex.fiber ( Excursion.class ).diff ( Excursion.CLEAR )
        );

    return
      new Recipes (
        features,
        activity,
        excursion
      );

  }

  /// Builds the fixed recording outside any measured path.

  public static Sample[] recording (
    final int sampleCount
  ) {

    if ( sampleCount <= 0 || sampleCount % ( MOTIF_BLOCKS * BLOCK_SIZE ) != 0 ) {
      throw new IllegalArgumentException (
        "sample count must be a positive multiple of " +
          ( MOTIF_BLOCKS * BLOCK_SIZE )
      );
    }

    final var samples =
      new Sample[sampleCount];

    var state =
      RECORDING_SEED;

    for ( int index = 0; index < sampleCount; index++ ) {

      state =
        state * 6364136223846793005L + 1442695040888963407L;

      final var block =
        index / BLOCK_SIZE;

      final var motifBlock =
        block % MOTIF_BLOCKS;

      final var offset =
        index % BLOCK_SIZE;

      final var noise =
        (int) ( ( state >>> 58 ) & 63L ) - 32;

      final int amplitude;

      if ( motifBlock >= 8 && motifBlock <= 15 ||
        motifBlock >= 24 && motifBlock <= 27 ) {

        amplitude =
          ( offset & 1 ) == 0 ? 8192 : -8192;

      } else if ( motifBlock == 20 && offset == 32 ) {

        amplitude =
          ( block / MOTIF_BLOCKS & 1 ) == 0 ? 16_384 : -16_384;

      } else {

        amplitude =
          noise;

      }

      samples[index] =
        new Sample ( index, amplitude );

    }

    return
      samples;

  }

  private static void requireEqual (
    final String label,
    final Object expected,
    final Object actual
  ) {

    if ( !expected.equals ( actual ) ) {
      throw new IllegalStateException (
        label + ": expected " + expected + ", observed " + actual
      );
    }

  }

  private static Sample[] requiredCases () {

    return
      concatenate (
        block ( 0, _ -> 0 ),
        block ( 1, _ -> 4096 ),
        block ( 2, _ -> -4096 ),
        block ( 3, i -> i == 0 ? -12_000 : 0 ),
        block ( 4, _ -> -12_000 ),
        block ( 5, _ -> 12_000 ),
        block ( 6, _ -> 0 )
      );

  }

  public static void verify () {

    verifyHandCalculatedCases ();

    final var recording =
      recording ( SAMPLE_COUNT );

    final var expected =
      expected ( recording );

    requireEqual ( "recording measurements", 256, expected.measurements ().length );
    requireEqual ( "recording activity transitions", 32, expected.activityTransitions ().length );
    requireEqual ( "recording excursion transitions", 16, expected.excursionTransitions ().length );

    final var cortex =
      Substrates.cortex ();

    final var recipes =
      recipes ( cortex );

    verifyExactPositionsAndReplay ( cortex, recipes, recording, expected, true );

    final var requiredCases =
      requiredCases ();

    verifyExactPositionsAndReplay (
      cortex,
      recipes,
      requiredCases,
      expected ( requiredCases ),
      true
    );

    final var boundaryCases =
      boundaryCases ();

    verifyExactPositionsAndReplay (
      cortex,
      recipes,
      boundaryCases,
      expected ( boundaryCases ),
      false
    );

    verifyCompletionAndSplit ( cortex, recipes );
    verifyIndependentMaterializations ( cortex, recipes );

  }

  private static void verifyBlock (
    final Sample[] samples,
    final long energy,
    final int peak,
    final Activity activity,
    final Excursion excursion
  ) {

    final var measurement =
      expected ( samples ).measurements ()[0];

    requireEqual ( "block energy", energy, measurement.energy () );
    requireEqual ( "block peak", peak, measurement.peak () );
    requireEqual ( "block activity", activity, measurement.activity () );
    requireEqual ( "block excursion", excursion, measurement.excursion () );

  }

  private static void verifyCompletionAndSplit (
    final Cortex cortex,
    final Recipes recipes
  ) {

    final var samples =
      block ( 0, i -> ( i & 1 ) == 0 ? 4096 : -4096 );

    final var expected =
      expected ( samples );

    final var splitOutput =
      new OutputBuffer ( 1 );

    final var wholeOutput =
      new OutputBuffer ( 1 );

    try (
      final var split = new MonitorGraph ( cortex, recipes, splitOutput );
      final var whole = new MonitorGraph ( cortex, recipes, wholeOutput )
    ) {

      for ( int i = 0; i < BLOCK_SIZE - 1; i++ ) {
        split.emit ( samples[i] );
      }

      split.await ();
      requireEqual ( "no early measurement", 0, splitOutput.measurementCount () );

      split.emit ( samples[BLOCK_SIZE - 1] );
      split.await ();
      requireEqual ( "one completed measurement", 1, splitOutput.measurementCount () );

      whole.emit ( samples );
      whole.await ();

      verifyOutput ( expected, splitOutput );
      verifyOutput ( expected, wholeOutput );

    }

    final var resumedOutput =
      new OutputBuffer ( 1 );

    final var uninterruptedOutput =
      new OutputBuffer ( 1 );

    try (
      final var resumed = new MonitorGraph ( cortex, recipes, resumedOutput );
      final var uninterrupted = new MonitorGraph ( cortex, recipes, uninterruptedOutput )
    ) {

      for ( int i = 0; i < BLOCK_SIZE / 2; i++ ) {
        resumed.emit ( samples[i] );
      }

      resumed.await ();

      for ( int i = BLOCK_SIZE / 2; i < BLOCK_SIZE; i++ ) {
        resumed.emit ( samples[i] );
      }

      uninterrupted.emit ( samples );
      resumed.await ();
      uninterrupted.await ();

      verifyOutput ( expected, resumedOutput );
      verifyOutput ( expected, uninterruptedOutput );

    }

  }

  private static void verifyExactPositionsAndReplay (
    final Cortex cortex,
    final Recipes recipes,
    final Sample[] samples,
    final Expected expected,
    final boolean replay
  ) {

    final var output =
      new OutputBuffer ( expected.measurements ().length );

    try ( final var graph = new MonitorGraph ( cortex, recipes, output ) ) {

      var nextActivity =
        0;

      var nextExcursion =
        0;

      for ( int block = 0; block < expected.measurements ().length; block++ ) {

        final var end =
          ( block + 1 ) * BLOCK_SIZE;

        for ( int i = block * BLOCK_SIZE; i < end; i++ ) {
          graph.emit ( samples[i] );
        }

        graph.await ();

        requireEqual ( "measurement boundary " + block, block + 1, output.measurementCount () );
        requireEqual ( "measurement at boundary " + block,
          expected.measurements ()[block], output.measurement ( block ) );

        if ( nextActivity < expected.activityTransitions ().length &&
          expected.activityTransitions ()[nextActivity].blockIndex () == block ) {

          requireEqual ( "activity at block " + block,
            expected.activityTransitions ()[nextActivity].activity (),
            output.activityTransition ( nextActivity ) );
          nextActivity++;

        }

        requireEqual ( "activity count at block " + block, nextActivity, output.activityCount () );

        if ( nextExcursion < expected.excursionTransitions ().length &&
          expected.excursionTransitions ()[nextExcursion].blockIndex () == block ) {

          requireEqual ( "excursion at block " + block,
            expected.excursionTransitions ()[nextExcursion].excursion (),
            output.excursionTransition ( nextExcursion ) );
          nextExcursion++;

        }

        requireEqual ( "excursion count at block " + block, nextExcursion, output.excursionCount () );

      }

      verifyOutput ( expected, output );

      if ( replay ) {

        output.reset ();
        graph.emit ( samples );
        graph.await ();
        verifyOutput ( expected, output );

      }

    }

  }

  private static void verifyHandCalculatedCases () {

    verifyBlock ( block ( 0, _ -> 0 ), 0L, 0, Activity.QUIET, Excursion.CLEAR );
    verifyBlock ( block ( 0, i -> ( i & 1 ) == 0 ? 4096 : -4096 ),
      ACTIVITY_THRESHOLD, 4096, Activity.ACTIVE, Excursion.CLEAR );
    verifyBlock ( block ( 0, i -> i == 17 ? 12_000 : 0 ),
      144_000_000L, 12_000, Activity.QUIET, Excursion.PEAK );
    verifyBlock ( block ( 0, _ -> 12_000 ),
      9_216_000_000L, 12_000, Activity.ACTIVE, Excursion.PEAK );
    verifyBlock ( block ( 0, i -> i == 0 ? Short.MIN_VALUE : 0 ),
      1_073_741_824L, 32_768, Activity.ACTIVE, Excursion.PEAK );
    verifyBlock ( block ( 0, i -> i == 0 ? Short.MAX_VALUE : 0 ),
      1_073_676_289L, 32_767, Activity.QUIET, Excursion.PEAK );

    requireEqual ( "energy below threshold", Activity.QUIET,
      classifyActivity ( ACTIVITY_THRESHOLD - 1L ) );
    requireEqual ( "energy at threshold", Activity.ACTIVE,
      classifyActivity ( ACTIVITY_THRESHOLD ) );
    requireEqual ( "energy above threshold", Activity.ACTIVE,
      classifyActivity ( ACTIVITY_THRESHOLD + 1L ) );
    requireEqual ( "peak below threshold", Excursion.CLEAR,
      classifyExcursion ( PEAK_THRESHOLD - 1 ) );
    requireEqual ( "peak at threshold", Excursion.PEAK,
      classifyExcursion ( PEAK_THRESHOLD ) );
    requireEqual ( "peak above threshold", Excursion.PEAK,
      classifyExcursion ( PEAK_THRESHOLD + 1 ) );

    final var oracle =
      expected ( requiredCases () );

    requireEqual ( "stable activity transition count", 4, oracle.activityTransitions ().length );
    requireEqual ( "stable peak transition count", 2, oracle.excursionTransitions ().length );
    requireEqual ( "return to quiet", Activity.QUIET,
      oracle.activityTransitions ()[3].activity () );
    requireEqual ( "return to clear", Excursion.CLEAR,
      oracle.excursionTransitions ()[1].excursion () );

  }

  private static void verifyIndependentMaterializations (
    final Cortex cortex,
    final Recipes recipes
  ) {

    final var quiet0 =
      block ( 0, _ -> 0 );

    final var active0 =
      block ( 0, _ -> 4096 );

    final var active1 =
      block ( 1, _ -> -4096 );

    final var quiet1 =
      block ( 1, _ -> 0 );

    final var firstOutput =
      new OutputBuffer ( 2 );

    final var secondOutput =
      new OutputBuffer ( 2 );

    try (
      final var first = new MonitorGraph ( cortex, recipes, firstOutput );
      final var second = new MonitorGraph ( cortex, recipes, secondOutput )
    ) {

      for ( int i = 0; i < BLOCK_SIZE / 2; i++ ) {
        first.emit ( quiet0[i] );
      }

      first.await ();
      second.emit ( active0 );
      second.await ();

      for ( int i = BLOCK_SIZE / 2; i < BLOCK_SIZE; i++ ) {
        first.emit ( quiet0[i] );
      }

      first.emit ( active1 );
      second.emit ( quiet1 );
      first.await ();
      second.await ();

      verifyOutput ( expected ( concatenate ( quiet0, active1 ) ), firstOutput );
      verifyOutput ( expected ( concatenate ( active0, quiet1 ) ), secondOutput );

    }

  }

  public static void verifyOutput (
    final Expected expected,
    final OutputBuffer actual
  ) {

    requireEqual (
      "measurement count",
      expected.measurements ().length,
      actual.measurementCount ()
    );

    requireEqual (
      "activity transition count",
      expected.activityTransitions ().length,
      actual.activityCount ()
    );

    requireEqual (
      "excursion transition count",
      expected.excursionTransitions ().length,
      actual.excursionCount ()
    );

    for ( int i = 0; i < actual.measurementCount (); i++ ) {
      requireEqual ( "measurement " + i, expected.measurements ()[i], actual.measurement ( i ) );
    }

    for ( int i = 0; i < actual.activityCount (); i++ ) {
      requireEqual (
        "activity transition " + i,
        expected.activityTransitions ()[i].activity (),
        actual.activityTransition ( i )
      );
    }

    for ( int i = 0; i < actual.excursionCount (); i++ ) {
      requireEqual (
        "excursion transition " + i,
        expected.excursionTransitions ()[i].excursion (),
        actual.excursionTransition ( i )
      );
    }

  }

  public enum Activity {
    QUIET,
    ACTIVE
  }

  public enum Excursion {
    CLEAR,
    PEAK
  }

  public record ActivityEvent(
    int blockIndex,
    Activity activity
  ) { }

  public record BlockMetrics(
    int index,
    long energy,
    int peak
  ) {

    public Activity activity () {

      return classifyActivity ( energy );

    }

    public Excursion excursion () {

      return classifyExcursion ( peak );

    }

  }

  /// Mutable state owned by one projected-scan materialization.

  public static final class BlockState {

    private int  count;
    private long energy;
    private int  peak;

    public BlockState add (
      final Sample sample
    ) {

      final var amplitude =
        sample.amplitude ();

      energy +=
        (long) amplitude * amplitude;

      peak =
        Math.max ( peak, Math.abs ( amplitude ) );

      count++;

      return
        this;

    }

    public BlockMetrics snapshotIfComplete (
      final Sample sample
    ) {

      if ( count != BLOCK_SIZE ) {
        return null;
      }

      final var result =
        new BlockMetrics (
          sample.index () / BLOCK_SIZE,
          energy,
          peak
        );

      count =
        0;

      energy =
        0L;

      peak =
        0;

      return
        result;

    }

  }

  /// Matched caller-thread implementation for the JMH control rows.

  public static final class DirectMonitor {

    private final OutputBuffer output;

    private int       count;
    private long      energy;
    private int       peak;
    private Activity  previousActivity  = Activity.QUIET;
    private Excursion previousExcursion = Excursion.CLEAR;

    public DirectMonitor (
      final OutputBuffer output
    ) {

      this.output =
        output;

    }

    public void process (
      final Sample[] samples
    ) {

      for ( final var sample : samples ) {

        final var amplitude =
          sample.amplitude ();

        energy +=
          (long) amplitude * amplitude;

        peak =
          Math.max ( peak, Math.abs ( amplitude ) );

        if ( ++count != BLOCK_SIZE ) {
          continue;
        }

        final var measurement =
          new BlockMetrics (
            sample.index () / BLOCK_SIZE,
            energy,
            peak
          );

        output.record ( measurement );

        final var activity =
          measurement.activity ();

        if ( activity != previousActivity ) {
          output.record ( activity );
          previousActivity = activity;
        }

        final var excursion =
          measurement.excursion ();

        if ( excursion != previousExcursion ) {
          output.record ( excursion );
          previousExcursion = excursion;
        }

        count =
          0;

        energy =
          0L;

        peak =
          0;

      }

    }

  }

  public record ExcursionEvent(
    int blockIndex,
    Excursion excursion
  ) { }

  /// Independent expected streams produced without using [BlockState].

  public record Expected(
    BlockMetrics[] measurements,
    ActivityEvent[] activityTransitions,
    ExcursionEvent[] excursionTransitions
  ) {

    public int deliveryCount () {

      return
        measurements.length +
          activityTransitions.length +
          excursionTransitions.length;

    }

  }

  /// One materialized monitor graph on one circuit.

  public static final class MonitorGraph
    implements AutoCloseable {

    private final Circuit         circuit;
    private final OutputBuffer    output;
    private final Pipe < Sample > input;

    public MonitorGraph (
      final Cortex cortex,
      final Recipes recipes,
      final OutputBuffer output
    ) {

      this.output =
        output;

      circuit =
        cortex.circuit ();

      final Pipe < BlockMetrics > activity =
        recipes.activity ().pipe (
          circuit.pipe ( output::record )
        );

      final Pipe < BlockMetrics > excursion =
        recipes.excursion ().pipe (
          circuit.pipe ( output::record )
        );

      input =
        recipes.features ().pipe (
          circuit.pipe (
            measurement -> {

              output.record ( measurement );
              activity.emit ( measurement );
              excursion.emit ( measurement );

            }
          )
        );

      circuit.await ();

    }

    public void await () {

      circuit.await ();

    }

    @Override
    public void close () {

      circuit.closeAwait ();

    }

    public void emit (
      final Sample sample
    ) {

      input.emit ( sample );

    }

    public void emit (
      final Sample[] samples
    ) {

      for ( final var sample : samples ) {
        input.emit ( sample );
      }

    }

    public OutputBuffer output () {

      return output;

    }

  }

  /// Reusable result storage, written by one circuit context or one caller.

  public static final class OutputBuffer {

    private final BlockMetrics[] measurements;
    private final Activity[]     activityTransitions;
    private final Excursion[]    excursionTransitions;
    private final Runnable       observed;

    private int measurementCount;
    private int activityCount;
    private int excursionCount;

    public OutputBuffer (
      final int blockCapacity
    ) {

      this ( blockCapacity, () -> {
      } );

    }

    public OutputBuffer (
      final int blockCapacity,
      final Runnable observed
    ) {

      measurements =
        new BlockMetrics[blockCapacity];

      activityTransitions =
        new Activity[blockCapacity];

      excursionTransitions =
        new Excursion[blockCapacity];

      this.observed =
        observed;

    }

    public int activityCount () {

      return activityCount;

    }

    public Activity activityTransition (
      final int index
    ) {

      return activityTransitions[index];

    }

    public int excursionCount () {

      return excursionCount;

    }

    public Excursion excursionTransition (
      final int index
    ) {

      return excursionTransitions[index];

    }

    public BlockMetrics measurement (
      final int index
    ) {

      return measurements[index];

    }

    public int measurementCount () {

      return measurementCount;

    }

    public void reset () {

      Arrays.fill ( measurements, 0, measurementCount, null );
      Arrays.fill ( activityTransitions, 0, activityCount, null );
      Arrays.fill ( excursionTransitions, 0, excursionCount, null );

      measurementCount =
        0;

      activityCount =
        0;

      excursionCount =
        0;

    }

    private void record (
      final BlockMetrics measurement
    ) {

      measurements[measurementCount++] =
        measurement;

      observed.run ();

    }

    private void record (
      final Activity activity
    ) {

      activityTransitions[activityCount++] =
        activity;

      observed.run ();

    }

    private void record (
      final Excursion excursion
    ) {

      excursionTransitions[excursionCount++] =
        excursion;

      observed.run ();

    }

  }

  /// Recipes are immutable and hold no materialized processing state.

  public record Recipes(
    Flow < Sample, BlockMetrics > features,
    Flow < BlockMetrics, Activity > activity,
    Flow < BlockMetrics, Excursion > excursion
  ) { }

  public record Sample(
    int index,
    int amplitude
  ) {

    public Sample {

      if ( amplitude < Short.MIN_VALUE || amplitude > Short.MAX_VALUE ) {
        throw new IllegalArgumentException (
          "amplitude outside the signed 16-bit range: " + amplitude
        );
      }

    }

  }

}
