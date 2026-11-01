// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.showcase;

import io.humainary.substrates.api.Substrates;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.stream.DoubleStream;

///
/// A finite workload that Substrates drives itself: tokens carried through a population of
/// circuits.
///
/// The circuits are split into one chain per token. Each circuit makes `--burst` deliveries with
/// the token, the first arriving from the previous circuit and the rest from itself, then passes
/// it to the next circuit in its chain; the last circuit releases the main thread instead. The
/// main thread builds the chains once, then for each round seeds the tokens, waits for every
/// chain to finish, checks every circuit's count, and reports. One scope holds every circuit and
/// closes them all at the end.
///
/// `--circuits` sets the population, `--tokens` how many chains progress at once, and `--burst`
/// the work each circuit does per round. With as many tokens as circuits, every circuit feeds
/// itself. Measured rounds repeat until they have run for `--minutes`. Two warmup rounds, reported
/// but not counted, precede them. The first starts every circuit's worker and compiles the code.
/// The second takes what the first one's collections cost: a virtual thread cannot reuse a stack
/// chunk that a collection has seen, so each circuit allocates a new one the next time it goes
/// idle.
///

public final class Showcase
  implements Substrates {

  private static final Object TOKEN = new Object ();

  private static final Semaphore FINISHED = new Semaphore ( 0 );

  private static final int WARMUPS = 2;

  /// The deliveries each token carries a round when `--burst` is left out: a few seconds on one
  /// processor, so a round lasts about as long whatever the population or the machine.
  private static final double TOKEN_DELIVERIES = 1e9;

  private static final int PROCESSORS = Runtime.getRuntime ().availableProcessors ();

  private static final com.sun.management.OperatingSystemMXBean SYSTEM =
    ManagementFactory.getPlatformMXBean ( com.sun.management.OperatingSystemMXBean.class );

  private static final com.sun.management.ThreadMXBean THREADS =
    (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean ();

  private static final boolean ALLOCATION =
    THREADS.isThreadAllocatedMemorySupported () && THREADS.isThreadAllocatedMemoryEnabled ();

  private static final List < GarbageCollectorMXBean > COLLECTORS =
    ManagementFactory.getGarbageCollectorMXBeans ();

  private Showcase () { }

  public static void main (
    final String[] args
  ) throws InterruptedException {

    final var options = new LinkedHashMap < String, Double > ();
    options.put ( "circuits", 1e6 );
    options.put ( "tokens", (double) PROCESSORS );
    options.put ( "burst", null );
    options.put ( "minutes", 5d );

    for ( var i = 0; i < args.length; i += 2 ) {
      if ( !args[i].startsWith ( "--" ) || !options.containsKey ( args[i].substring ( 2 ) ) || i + 1 == args.length ) {
        throw new IllegalArgumentException ( "usage: Showcase [--circuits n] [--tokens n] [--burst n] [--minutes n]" );
      }
      options.put ( args[i].substring ( 2 ), Double.parseDouble ( args[i + 1] ) );
    }

    final var size = Math.toIntExact ( options.get ( "circuits" ).longValue () );
    final var tokens = Math.toIntExact ( options.get ( "tokens" ).longValue () );
    final var minutes = options.get ( "minutes" );
    final var burst =
      options.get ( "burst" ) == null
        ? Math.max ( 1L, Math.round ( TOKEN_DELIVERIES * tokens / size ) )
        : options.get ( "burst" ).longValue ();

    if ( burst < 1 || tokens < 1 || tokens > size || !( minutes > 0 ) ) {
      throw new IllegalArgumentException ( "every option must be positive, and --tokens at most --circuits" );
    }

    final var cortex = Substrates.cortex ();
    final var before = heap ();
    final var building = System.nanoTime ();
    final var scope = cortex.scope ();
    final var relays = new Relay[size];

    for ( var i = 0; i < size; i++ ) {
      final var circuit = scope.register ( cortex.circuit () );
      relays[i] = new Relay ();
      relays[i].self = circuit.pipe ( relays[i] );
    }

    for ( var token = 0; token < tokens; token++ ) {
      final var last = first ( token + 1, size, tokens ) - 1;
      for ( var i = first ( token, size, tokens ); i < last; i++ ) {
        relays[i].next = relays[i + 1].self;
      }
    }

    final var built = System.nanoTime () - building;
    final var deliveries = size * burst;
    final long handoffs = size - tokens;
    final var budget = (long) ( minutes * 60e9 );

    System.out.printf (
      Locale.ROOT,
      "%s on Java %s, %d processors, %s heap%n" +
        "%,d circuits in %,d chains, built in %.2f s; a round is %,d deliveries, %,d per circuit, and %,d handoffs between circuits%n" +
        "rounds measured for %,.0f s after %d warmup rounds%n%n" +
        "%-10s %8s %13s %11s %10s %14s %11s %11s%n",
      cortex.getClass ().getName (), Runtime.version (), PROCESSORS, bytes ( Runtime.getRuntime ().maxMemory () ),
      size, tokens, built / 1e9, deliveries, burst, handoffs,
      budget / 1e9, WARMUPS,
      "", "seconds", "deliveries/s", "handoffs/s", "busy", "ns cpu/deliv.", "B/visit", "collections"
    );

    var total = new Reading ( 0, 0, 0, 0 );
    final var rates = DoubleStream.builder ();

    for ( var round = 1 - WARMUPS; round < 1 || total.nanos () < budget; round++ ) {

      for ( final var relay : relays ) {
        relay.target += burst;
      }

      final var start = Reading.now ();

      for ( var token = 0; token < tokens; token++ ) {
        relays[first ( token, size, tokens )].self.emit ( TOKEN );
      }

      FINISHED.acquire ( tokens );

      final var spent = Reading.now ().minus ( start );

      for ( final var relay : relays ) {
        if ( relay.made != relay.target ) {
          throw new IllegalStateException ( "a circuit made " + relay.made + " deliveries, not " + relay.target );
        }
      }

      row ( round > 0 ? "round " + round : "warmup " + ( round + WARMUPS ), spent, deliveries, handoffs, size );

      if ( round > 0 ) {
        total = total.plus ( spent );
        rates.add ( deliveries / ( spent.nanos () / 1e9 ) );
      }

    }

    // Measured after the rounds, since a forced collection inflates the next round's allocation.
    // Idle circuits do not hold their relays, so the fence keeps them in the measurement.
    final var held = heap () - before;
    Reference.reachabilityFence ( relays );

    scope.close ();

    final var measured = rates.build ().toArray ();
    final var rounds = measured.length;
    final var seconds = total.nanos () / 1e9;
    final var rate = rounds * deliveries / seconds;
    final var mean = Arrays.stream ( measured ).average ().orElseThrow ();
    final var cv = Math.sqrt ( Arrays.stream ( measured ).map ( r -> ( r - mean ) * ( r - mean ) ).sum () / rounds ) / mean;

    row ( "rounds", total, rounds * deliveries, rounds * handoffs, (long) rounds * size );

    System.out.printf (
      Locale.ROOT,
      "%n%,d deliveries in %.1f s over %d rounds, varying by %.1f%% (cv); %,d B of heap held per circuit after the rounds%n" +
        "summary circuits=%d tokens=%d burst=%d minutes=%s rounds=%d seconds=%.6f rate=%.6g handoff_rate=%.6g cv=%.4f" +
        " cpu=%.4f cpu_ns=%.4f bytes_per_visit=%s collections=%d held_per_circuit=%d built_seconds=%.3f%n",
      rounds * deliveries, seconds, rounds, cv * 100, held / size,
      size, tokens, burst, minutes, rounds, seconds, rate, rounds * handoffs / seconds, cv,
      total.cpu () / ( seconds * 1e9 * PROCESSORS ), total.cpu () / (double) ( rounds * deliveries ),
      perVisit ( "%d", total, (long) rounds * size ), total.collections (), held / size, built / 1e9
    );

  }

  private static void row (
    final String label,
    final Reading spent,
    final long deliveries,
    final long handoffs,
    final long visits
  ) {

    final var seconds = spent.nanos () / 1e9;

    System.out.printf (
      Locale.ROOT,
      "%-10s %8.3f %13s %11s %9.0f%% %14.1f %11s %11d%n",
      label,
      seconds,
      si ( deliveries / seconds ),
      si ( handoffs / seconds ),
      spent.cpu () / ( seconds * 1e9 * PROCESSORS ) * 100,
      spent.cpu () / (double) deliveries,
      perVisit ( "%,d", spent, visits ),
      spent.collections ()
    );

  }

  /// The bytes allocated per circuit visit, or `-` where the VM cannot count them. Each round
  /// visits every circuit once, whether its token came from the seed or from another circuit.
  private static String perVisit (
    final String format,
    final Reading spent,
    final long visits
  ) {

    return
      ALLOCATION ? String.format ( Locale.ROOT, format, spent.allocated () / visits ) : "-";

  }

  /// The first circuit of a token's chain: the chains split the circuits evenly, in order.
  private static int first (
    final int token,
    final int size,
    final int tokens
  ) {

    return
      (int) ( (long) token * size / tokens );

  }

  /// The heap in use once a collection has cleared everything unreachable.
  private static long heap () {

    System.gc ();

    return
      ManagementFactory.getMemoryMXBean ().getHeapMemoryUsage ().getUsed ();

  }

  private static String si (
    final double value
  ) {

    if ( value >= 1e9 ) return String.format ( Locale.ROOT, "%.2f G", value / 1e9 );
    if ( value >= 1e6 ) return String.format ( Locale.ROOT, "%.1f M", value / 1e6 );
    if ( value >= 1e3 ) return String.format ( Locale.ROOT, "%.1f k", value / 1e3 );
    return String.format ( Locale.ROOT, "%.1f ", value );

  }

  private static String bytes (
    final long value
  ) {

    if ( value >= 1L << 30 ) return String.format ( Locale.ROOT, "%.1f GiB", value / (double) ( 1L << 30 ) );
    return String.format ( Locale.ROOT, "%.1f MiB", value / (double) ( 1L << 20 ) );

  }

  /// One circuit's part in a chain. Each delivery counts toward `target`, which the main thread
  /// raises by a burst before every round; the delivery that reaches it passes the token on, or
  /// finishes the chain when there is no next circuit.
  ///
  /// The finish is a branch here rather than a pipe of its own: a second receptor type makes the
  /// worker's delivery call site bimorphic, which measurably slows a self-feeding circuit.
  private static final class Relay
    implements Receptor < Object > {

    private Pipe < Object > self;
    private Pipe < Object > next;
    private long            made;
    private long            target;

    @Override
    public void receive (
      final Object token
    ) {

      if ( ++made < target ) {
        self.emit ( token );
      } else if ( next != null ) {
        next.emit ( token );
      } else {
        FINISHED.release ();
      }

    }

  }

  /// The clock, the process's processor time, the bytes all threads have allocated, and the
  /// collections run, read together.
  private record Reading(
    long nanos,
    long cpu,
    long allocated,
    long collections
  ) {

    static Reading now () {

      var collections = 0L;
      for ( final var collector : COLLECTORS ) {
        collections += Math.max ( 0, collector.getCollectionCount () );
      }

      return
        new Reading (
          System.nanoTime (),
          SYSTEM.getProcessCpuTime (),
          ALLOCATION ? THREADS.getTotalThreadAllocatedBytes () : 0L,
          collections
        );

    }

    Reading minus (
      final Reading earlier
    ) {

      return
        new Reading (
          nanos - earlier.nanos,
          cpu - earlier.cpu,
          allocated - earlier.allocated,
          collections - earlier.collections
        );

    }

    Reading plus (
      final Reading other
    ) {

      return
        new Reading (
          nanos + other.nanos,
          cpu + other.cpu,
          allocated + other.allocated,
          collections + other.collections
        );

    }

  }

}
