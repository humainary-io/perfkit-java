// Copyright (c) 2026 William David Louth

package io.humainary.perfkit.jmh.substrates;

import io.humainary.perfkit.jmh.Tally;
import io.humainary.perfkit.jmh.TerminalVerification;
import io.humainary.substrates.api.Substrates;
import org.openjdk.jmh.annotations.*;

import java.util.Arrays;

import static java.lang.System.arraycopy;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.openjdk.jmh.annotations.Level.*;
import static org.openjdk.jmh.annotations.Mode.AverageTime;

///
/// Benchmark for divide-and-conquer propagation through a circuit.
///
/// A top-down merge sort is expressed as emissions over a pre-built node tree,
/// then measured against the same sort run as plain recursion. What separates
/// the two is the cost of routing the recursion through a circuit; see
/// [What The Difference Is](#what-the-difference-is) before quoting it as
/// anything narrower.
///
/// ## Why Merge Sort
///
/// Merge sort is the divide-and-conquer whose recursion tree is **data
/// independent**: the split points follow from the input length alone, never
/// from the values. Every [Node] can therefore be allocated once at iteration
/// setup and emitted by reference, so no invocation allocates a task
/// descriptor. Quicksort cannot do this — its tree depends on where pivots
/// land — and a recursive Fibonacci, the other textbook fork/join example,
/// carries no work to amortize the propagation against.
///
/// ## Shape
///
/// The topology exercises three things at once that the rest of the suite
/// measures separately:
///
/// - **Fan-out**: the divide step emits two children, so every internal node
///   dispatches at breadth 2. Whatever a provider can do for a cascade with one
///   emission in flight at a time, it cannot do here. `PipeOps` prices breadth
///   3 at one inlet; this prices breadth 2 at every level of a deep tree.
/// - **Depth**: the tree is `log2(LENGTH / GRAIN)` edges deep — one more than
///   that in levels of nodes — the axis `StemOps` sweeps against a name
///   hierarchy rather than a workload.
/// - **Reversal**: the conquer step sends completions back up the same
///   topology, the flow reversal `CyclicOps` covers as a self-feeding cycle.
///
/// ## The Latch Is Hand-Rolled
///
/// A parent merges only once **both** children have reported. There is no
/// fan-in primitive to express that, so it is a counter per node: each child
/// completion increments its parent's slot, and the second one fires the
/// merge. Two rows measure the same latch through different state:
/// [#sort_pinned] reads the counter array out of a [Pin], and
/// [#sort_field] reads the identical array from a plain field. Both index the
/// same `int[]` and perform the same arithmetic on it, so what separates them
/// is one Pin access per completion against one field read: the owner-context
/// guard, plus whatever indirection the provider's Pin puts in front of the
/// value and whatever the two shapes compile to. `PinOps` compares that access
/// against a bare field in a loop; this asks whether the comparison holds
/// inside a real callback.
///
/// The latch is self-clearing — a slot returns to zero as it fires — so no
/// invocation pays to reset the tree.
///
/// ## Normalization
///
/// All three rows are `@OperationsPerInvocation(DISPATCHES)`, counting the
/// **dispatches the substrate rows perform**: every node is dispatched once
/// descending and once ascending, so a perfect tree over [#LENGTH] elements at
/// a [#GRAIN] leaf gives `2 * (2 * (LENGTH / GRAIN) - 1)` = [#DISPATCHES].
///
/// [#sort_sequential] performs the same sorts and the same merges and **no**
/// dispatches, yet carries that same divisor deliberately. It is not a rate of
/// anything it does; it is the baseline placed on the substrate rows' scale.
/// Reading it as a cost per operation of its own is not a comparison this class
/// supports.
///
/// ## What The Difference Is
///
/// Subtracting the baseline from a substrate row gives **the observed workload
/// overhead, normalized per dispatch**. It is not a measurement of dispatch
/// cost, and it does not bound one either: more than dispatch differs between
/// the two rows, and those differences are free to offset the cost of routing
/// as well as add to it — a traversal order that suits the workload, or two
/// shapes the JIT treats differently, can move the figure in either direction.
///
/// - **Order, and therefore locality.** The recursion merges a parent while its
///   children's ranges are still warm. The circuit drains a queue, so a parent
///   merges some distance after the children that filled it, over a working set
///   that has moved on.
/// - **The latch.** The substrate rows maintain a counter per internal node and
///   read it on every completion; the recursion needs none, because returning
///   from two calls *is* the join.
/// - **The tally.** Completion evidence is counted per dispatch on the
///   substrate rows and not at all on the baseline.
/// - **The thread.** The work runs on the circuit's worker while the caller
///   parks in `await()`; the baseline runs on the caller.
///
/// Isolating dispatch alone would need a fourth row walking the same tree in
/// the same queue order, with the same latch and tally, over a hand-rolled FIFO
/// instead of the circuit. Until that exists, quote the difference as what it
/// is.
///
/// [#LENGTH] and [#GRAIN] are constants rather than `@Param` because
/// `@OperationsPerInvocation` cannot vary with a parameter, and a divisor that
/// disagreed with the row it normalizes would be worse than a missing sweep.
/// To sweep either, add sibling methods with their own constants.
///
/// ## Why The Grain Is Fine
///
/// [#GRAIN] is deliberately small. Dispatches scale with `LENGTH / GRAIN` while
/// the sorting work scales with `LENGTH * log(LENGTH / GRAIN)`, so coarsening
/// the leaf puts more merging behind each dispatch and buries the routing cost
/// in it. A first configuration at a much coarser leaf resolved all three rows
/// to the same score within their error bars — a real result about granularity,
/// since past some subtask size the overhead becomes indistinguishable within
/// measurement uncertainty, but at that point the rows measure merge sort
/// rather than the substrate.
///
/// The committed constants were chosen by measuring where the rows separate,
/// not by formula, and that separation is a property of the provider and the
/// machine as much as of the tree. Confirm it holds before reading these rows,
/// and re-choose by measuring rather than by inheriting the choice.
///
/// The other constraint is the drain. Each invocation ends in one `await()`,
/// whose rendezvous is charged to every operation the divisor counts, so
/// [#DISPATCHES] has to be large enough for it to disappear. How large depends
/// on what the rendezvous costs a given provider, which is a thing to measure
/// before reading any score here — a benchmark dominated by its drain reports
/// the drain.
///
/// ## Allocation
///
/// Run this class under `-prof gc` as a matter of course. The score alone does
/// not say what a branching cascade costs a provider to carry; allocation per
/// dispatch is the second half of the answer, and the two move independently.
///
/// The reading is comparative, and the baseline supplies the control: whatever
/// [#sort_sequential] reports is the allocation this workload performs on its
/// own, so anything a substrate row reports above that is what the provider
/// spends carrying the cascade. A provider that carries a branching cascade
/// without per-dispatch cost reports a baseline-like figure; one that materializes
/// something per dispatch reports its width. Both are conforming, the API says
/// nothing either way, and separating them is what this row is for.
///
/// Do not carry a number from one provider into a claim about another, and do
/// not read a change in the figure as a regression until the same provider has
/// been measured twice.
///
/// ## What It Does Not Measure
///
/// Not parallel speed. A circuit processes emissions in a single ordered
/// sequence, so routing this sort through one buys no parallelism over the
/// recursion it is compared against — the question is what that routing costs,
/// not which of the two wins. Genuine parallelism would put
/// subtrees on separate circuits, which is a cross-circuit dispatch this suite
/// does not yet cover.
///
/// @since 3.1
///

@State ( Scope.Benchmark )
@BenchmarkMode ( AverageTime )
@OutputTimeUnit ( NANOSECONDS )
@Fork ( 1 )
@Warmup ( iterations = 3, time = 1 )
@Measurement ( iterations = 5, time = 1 )

public class DivideConquerOps
  implements Substrates {

  /// Elements sorted per invocation.
  private static final int LENGTH = 65_536;

  /// Leaf width, below which a range is insertion sorted rather than split.
  private static final int GRAIN = 2;

  /// Leaves of the tree; [#GRAIN] divides [#LENGTH], and the quotient is a power
  /// of two because both are.
  private static final int LEAVES = LENGTH / GRAIN;

  /// Nodes in a perfect binary tree over [#LEAVES] leaves.
  private static final int NODES = 2 * LEAVES - 1;

  /// Every node dispatched once descending and once ascending.
  private static final int DISPATCHES = 2 * NODES;

  /// Fixed so every fork sorts the same permutation.
  private static final long SEED = 0x5DEECE66DL;

  // Dispatches counted by the circuit context, held off this state object.
  private final Tally         dispatches = new Tally ();
  private       Cortex        cortex;
  private       Circuit       circuit;
  private       int[]         master;
  private       int[]         expected;
  private       int[]         work;
  private       int[]         scratch;
  private       int[]         latches;
  private       Pin < int[] > latchPin;
  private       Node          root;
  private       Pipe < Node > pinnedDown;
  private       Pipe < Node > pinnedUp;
  private       Pipe < Node > fieldDown;
  private       Pipe < Node > fieldUp;

  /// Builds the node tree, assigning each node the latch slot it will use.

  private static Node build (
    final int lo,
    final int hi,
    final Node parent,
    final int[] next
  ) {

    final var id =
      next[0]++;

    if ( hi - lo <= GRAIN ) {

      return
        new Node ( id, lo, lo, hi, parent, null, null );

    }

    final var mid =
      ( lo + hi ) >>> 1;

    final var node =
      new Node ( id, lo, mid, hi, parent, null, null );

    node.left =
      build ( lo, mid, node, next );

    node.right =
      build ( mid, hi, node, next );

    return
      node;

  }

  private static void insertion (
    final int[] a,
    final int lo,
    final int hi
  ) {

    for ( int i = lo + 1; i < hi; i++ ) {

      final var v =
        a[i];

      var j =
        i - 1;

      while ( j >= lo && a[j] > v ) {

        a[j + 1] =
          a[j];

        j--;

      }

      a[j + 1] =
        v;

    }

  }

  private static void merge (
    final int[] a,
    final int[] buffer,
    final Node node
  ) {

    var i = node.lo;
    var j = node.mid;
    var k = node.lo;

    while ( i < node.mid && j < node.hi ) {

      buffer[k++] =
        a[i] <= a[j]
        ? a[i++]
        : a[j++];

    }

    while ( i < node.mid ) {
      buffer[k++] = a[i++];
    }

    while ( j < node.hi ) {
      buffer[k++] = a[j++];
    }

    arraycopy (
      buffer,
      node.lo,
      a,
      node.lo,
      node.hi - node.lo
    );

  }

  ///
  /// Restores the permutation so every invocation sorts unsorted input.
  ///
  /// Invocation-level fixtures are ordinarily unusable at this suite's scale;
  /// they are sound here because one invocation is a whole sort, milliseconds
  /// of work against a copy costing tens of microseconds outside the timer.
  ///

  @Setup ( Invocation )
  public void setupInvocation () {

    arraycopy (
      master,
      0,
      work,
      0,
      LENGTH
    );

  }

  @Setup ( Iteration )
  public void setupIteration () {

    dispatches.take ();

    circuit =
      cortex.circuit ();

    root =
      build (
        0,
        LENGTH,
        null,
        new int[]{0}
      );

    latchPin =
      circuit.pin (
        latches
      );

    // Each receptor reads its own pipe field at dispatch time, which is why the
    // fields are assigned rather than captured as locals.

    pinnedDown =
      circuit.pipe (
        node -> {

          dispatches.increment ();

          if ( node.left == null ) {

            insertion (
              work,
              node.lo,
              node.hi
            );

            pinnedUp.emit ( node );

          } else {

            pinnedDown.emit ( node.left );
            pinnedDown.emit ( node.right );

          }

        }
      );

    pinnedUp =
      circuit.pipe (
        node -> {

          dispatches.increment ();

          final var parent =
            node.parent;

          if ( parent == null ) {
            return;
          }

          final var slots =
            latchPin.get ();

          if ( ++slots[parent.id] == 2 ) {

            slots[parent.id] = 0;

            merge (
              work,
              scratch,
              parent
            );

            pinnedUp.emit ( parent );

          }

        }
      );

    fieldDown =
      circuit.pipe (
        node -> {

          dispatches.increment ();

          if ( node.left == null ) {

            insertion (
              work,
              node.lo,
              node.hi
            );

            fieldUp.emit ( node );

          } else {

            fieldDown.emit ( node.left );
            fieldDown.emit ( node.right );

          }

        }
      );

    fieldUp =
      circuit.pipe (
        node -> {

          dispatches.increment ();

          final var parent =
            node.parent;

          if ( parent == null ) {
            return;
          }

          final var slots =
            latches;

          if ( ++slots[parent.id] == 2 ) {

            slots[parent.id] = 0;

            merge (
              work,
              scratch,
              parent
            );

            fieldUp.emit ( parent );

          }

        }
      );

  }

  @Setup ( Trial )
  public void setupTrial () {

    cortex =
      Substrates.cortex ();

    master =
      new int[LENGTH];

    // A fixed linear congruential fill, so every fork sorts the same
    // permutation without depending on a generator implementation.

    var state =
      SEED;

    for ( int i = 0; i < LENGTH; i++ ) {

      state =
        state * 6364136223846793005L + 1442695040888963407L;

      master[i] =
        (int) ( state >>> 33 );

    }

    expected =
      master.clone ();

    Arrays.sort (
      expected
    );

    work =
      new int[LENGTH];

    scratch =
      new int[LENGTH];

    latches =
      new int[NODES];

  }

  ///
  /// Control for [#sort_pinned]: the same topology, the same latch array, read
  /// from a plain field instead of through a [Pin]. The gap between the two
  /// rows is one Pin access per completion against one field read.
  ///

  @Benchmark
  @OperationsPerInvocation ( DISPATCHES )
  public void sort_field (
    final TerminalVerification verification
  ) {

    verification.expect (
      dispatches,
      DISPATCHES
    );

    fieldDown.emit (
      root
    );

    circuit.await ();

  }

  ///
  /// Divide-and-conquer through the circuit, latch state held in a [Pin].
  ///
  /// One admission enters at the root; every subsequent dispatch is transit
  /// work on the circuit context. The invocation does not return until the
  /// circuit has drained, so the dispatch count and the timing boundary agree.
  ///

  @Benchmark
  @OperationsPerInvocation ( DISPATCHES )
  public void sort_pinned (
    final TerminalVerification verification
  ) {

    verification.expect (
      dispatches,
      DISPATCHES
    );

    pinnedDown.emit (
      root
    );

    circuit.await ();

  }

  ///
  /// Baseline for the two substrate rows: identical splits, identical leaf sorts,
  /// identical merges, walked by plain recursion with no circuit involved.
  ///
  /// Carries the substrate rows' divisor so the subtraction is direct; see the
  /// class Normalization note before reading its score on its own.
  ///

  @Benchmark
  @OperationsPerInvocation ( DISPATCHES )
  public int sort_sequential () {

    descend (
      root
    );

    return
      work[LENGTH >> 1];

  }

  @TearDown ( Iteration )
  public void tearDownIteration () {

    circuit.await ();
    circuit.close ();

    // Evidence that the topology sorted rather than merely dispatched.
    //
    // Compared against the whole expected permutation rather than checked for
    // ascending order: a merge that dropped or duplicated elements can leave an
    // array that is ordered and still wrong, and an ordering check passes it.

    if ( !Arrays.equals ( work, expected ) ) {

      throw new IllegalStateException (
        "benchmark did not reproduce the sorted permutation"
      );

    }

  }

  /// Walks the same tree by recursion, performing the same work.

  private void descend (
    final Node node
  ) {

    if ( node.left == null ) {

      insertion (
        work,
        node.lo,
        node.hi
      );

      return;

    }

    descend ( node.left );
    descend ( node.right );

    merge (
      work,
      scratch,
      node
    );

  }

  ///
  /// A subtask, allocated once per iteration and emitted by reference.
  ///
  /// Static and holding its parent explicitly rather than capturing an
  /// enclosing instance, per the suite's hot-path convention.
  ///

  private static final class Node {

    private final int  id;
    private final int  lo;
    private final int  mid;
    private final int  hi;
    private final Node parent;
    private       Node left;
    private       Node right;

    private Node (
      final int id,
      final int lo,
      final int mid,
      final int hi,
      final Node parent,
      final Node left,
      final Node right
    ) {

      this.id = id;
      this.lo = lo;
      this.mid = mid;
      this.hi = hi;
      this.parent = parent;
      this.left = left;
      this.right = right;

    }

  }

}
