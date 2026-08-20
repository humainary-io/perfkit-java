# Humainary Perfkit Benchmarks

This document defines the JMH measurement and publication workflow for Java providers of the
Substrates and Serventis APIs.

## What the Suite Measures

The suite measures operations that recur during system execution: emission and its boundaries,
operator chains, pooled lookup, name and state access, endpoint read/update, and pipe
materialization (which recurs per subject in a pooled conduit and scales with the subject
population).

Two categories are deliberately excluded from this scope:

**One-time wiring construction.** Fiber and Flow chains, `pool(...)` recipes, and subscriptions are
assembled once per configuration and reused indefinitely. Only recurring usage is benchmarked.

**Component lifecycle.** Circuit creation is dominated by virtual-thread startup and platform
scheduler effects that the harness cannot stabilize; lifecycle benchmarks yield high relative errors
that do not converge with additional iterations. Qualitatively, lifecycle costs are orders of
magnitude above steady-state operation costs, which is why circuits are designed to be long-lived.
If a specific lifecycle metric is required, measure it via a dedicated decision run and publish it
with environment details rather than maintaining a noisy benchmark row.

The suite focuses exclusively on the hot path. Every benchmark initializes fixtures at the trial or
iteration level and measures an active circuit, reporting steady-state costs without cold-path
comparisons.

### Inventory

207 benchmark methods across 32 classes, categorized into four groups:

1. **Core Substrates** — framework primitives across the `*Ops` classes: Bank, Basin, Cell, Conduit,
   Cortex, Cyclic, Fiber, Flow, Id, Mirror, Name, Pin, Pipe, PipeMap, Pool, Port, Relate, RunChange,
   Scan, Sink, State, Stem, Subject, and Window.
2. **Serventis extension** — `opt.data`: `QueueOps` represents the domain emit path across
   instrument domains. See [Serventis signals](#serventis-signals).
3. **Serventis SDK** — semantic ascent layer: `ScorecardOps`, lookup structures (SignMap, SignalMap,
   SignalSet), and `sdk.meta` (Sequencer, BracketSequencer, Cycle).
4. **Specialized patterns** — hot-path and batched execution shapes spanning across these layers.

Methods and rendered rows differ in count: `@Param` expands a method into one row per parameter
value. `StemOps` is the only parameterized class (sweeping hierarchy `depth`), expanding its 3
methods into 12 rows, resulting in 216 rows across the 207 methods.

### Duplicate Rows That Earn Their Place

Generally, each distinct measurement has a single benchmark row; measuring every sign, domain, or
enum constant would repeatedly test the same underlying path. However, three specific kinds of
duplication are intentionally retained:

**Per-class emission baselines.** `baseline_plain_batch` appears identically in `FiberOps`,
`FlowOps`, and `PipeMapOps`, and `baseline_empty_pipe_batch` appears identically in `PortOps` and
`CellOps` (all landing in the same 9–11 ns band as `PipeOps.async_emit_batch`). These controls
provide a local baseline measured in the same fork and under the same fixtures as the anchored rows,
avoiding cross-class comparison distortion.

**Target and control at the same scale.** Varied lookups are paired with loop-and-consume controls
that traverse the same array without executing the operation. The control benchmark must remain
stable; its execution time is diagnostic and is never subtracted from the target score.

**Identical-work pairs as self-calibration.** `QueueOps.emit_enqueue_batch` and
`QueueOps.emit_sign_batch` call two methods with identical one-line bodies on a final class. Because
their true performance difference is zero, any observed gap in a given run reflects
harness/environmental noise measured under actual fixtures. Any other difference of similar
magnitude in that run warrants equal skepticism.

`StemOps` provides a similar calibration: at `depth=1`, a leaf node has no ancestors, so all three
traversal mechanisms perform identical work and produce the same delivery count. Discrepancies among
these three rows indicate the resolution limit of that run before interpreting other results.

### Every Benchmark Is Runnable

Any benchmark found to measure no real work (e.g., deterministic loops eliminated by constant
folding, void loops without escaping observations, or state accumulation across invocations) is
deleted rather than disabled in place. Deleting is appropriate only after confirming that live
coverage exists elsewhere; otherwise, the workload must be repaired.

A benchmark with sound timing whose allocation cannot be cleanly attributed to the measured
operation remains runnable, with class documentation explaining why its `gc.alloc.rate.norm` should
not be published. `CyclicOps` is the single class in this category —
see [Allocation quantity and attribution](#allocation-quantity-and-attribution).

## Running Benchmarks

`jmh.sh` provides the unified command interface. Build once, allow the system to reach thermal and
background-load steady state, then execute:

```bash
# Configure once per checkout, then edit the coordinates in jmh.env
./jmh.sh env --init
./jmh.sh env                 # what the next command will use, and from where

# Build the benchmark jar
./jmh.sh build

# List or run benchmarks in the current jar
./jmh.sh list PipeOps
./jmh.sh run PipeOps.empty_emit_batch

# JMH's -r sets measurement duration
./jmh.sh run PipeOps -wi 5 -i 10 -f 3 -r 1s

# Named suites for common benchmark groups
./jmh.sh suites
./jmh.sh decision core
```

Explicit subcommands include `build`, `list`, `run`, `decision`, `allocation`, `sensitivity`,
`table`, and `compare`. The last five delegate to `jmh-decision.sh`, `jmh-allocation.sh`,
`jmh-sensitivity.sh`, and `jmh-table.sh`. Arguments specified after a benchmark pattern are passed
directly to JMH.

**The project is self-contained.** Runners automatically resolve their script directory (following
symlinks) so that the jar in `target/`, the version in `pom.xml`, and the output
directories under `results/` resolve consistently regardless of working directory. User-supplied
paths are resolved relative to the invocation working directory, so
`./jmh.sh table results/<run>/results.json` run from the checkout and
`./<checkout>/jmh.sh table <checkout>/results/<run>/results.json` run from the directory above it
both resolve correctly. `JMH_RESULTS_DIR` behaves identically, and artifact locations are reported
as absolute paths.

**Nothing measured is built here.** The build compiles this project's benchmarks and assembles the
jar; it never reads, builds, or installs the source of anything it measures, and it never reaches
into a sibling directory for one. The APIs and the provider alike are pre-built artifacts resolved
from the local Maven repository, which is the footing the TCK puts them on and the reason a
different provider or API release is measured by changing a version rather than a directory layout.

**Everything measured is named by coordinate.** Benchmarks measure the provider strictly through the
public APIs. The provider enters as a resolved artifact specified by `SPI_GROUP`, `SPI_ARTIFACT`,
and `SPI_VERSION`; the APIs enter at `SUBSTRATES_API_VERSION` and `SERVENTIS_API_VERSION`, which
default to the versions declared in `pom.xml` and are independent of this suite's own
version. Setting `SPI_ARTIFACT` activates the provider dependency; omitting it builds a suite
without a provider (allowing benchmarks to be listed, while run attempts clearly report the missing
provider).

**Settings live in `jmh.env`.** Every runner loads it from the project directory before doing
anything else, so a configured checkout runs with bare commands rather than restating coordinates on
each invocation. `./jmh.sh env --init` writes a starting file from `jmh.env.example`; `./jmh.sh env`
reports the resolved value of every setting and where it came from. A variable exported in the
calling shell always wins over the file, keeping a one-off a one-off, and `JMH_ENV=<path>` selects a
different file. `jmh.env` is untracked: it names artifacts on one machine, not a property of the
suite.

The staleness check monitors this project's source and `pom.xml`, plus the resolved provider and API
jars in Maven local storage. It invalidates the benchmark jar whenever a local build input or
measured artifact is newer — the guarantee that a result belongs to the code and build configuration
it names, held without the suite ever seeing how any dependency is built.

These three coordinates are **build-time inputs** recorded in the benchmark jar's metadata.
Subsequent commands read provider coordinates from this metadata. Passing differing `SPI_*`
environment variables during a run is rejected as an error to prevent measuring one provider while
reporting another.

Selecting among multiple providers packaged in the jar is a runtime option: `SPI_PROVIDER=<class>`
passes `-Dio.humainary.substrates.spi.provider` to the forked JVM via `-jvmArgsAppend`. Passing `-D`
directly on the runner command line is rejected by JMH's option parser. Decision runs record this
selection in metadata as `spi_provider_class` (defaulting to `<serviceloader>`).

This suite's own version is a literal in `pom.xml`, bumped there when the suite is
released. It names the benchmark jar the runners look for and is not settable from the environment:
it tracks the benchmarks, not the APIs or the provider they measure, and each of those moves on its
own schedule.

### Named Suites

Three named suites map to common benchmark patterns:

- `all`: the complete benchmark inventory.
- `core`: emission admission/drain boundaries, chaining/fan-out, pooled lookup target/control pairs,
  one Serventis emit path, and one semantic-ascent path.
- `lookup`: 1k/10k `Name.depth` target/control pairs (default for the sensitivity runner).

Quoted regular expressions are supported anywhere a suite name is accepted.

### Decision Runs

```bash
./jmh.sh decision '.*NameOps.name_depth_varied_batch_1k'
./jmh.sh decision '.*StemOps.stem_emit_batch' -p depth=5
```

Use decision runs for actionable performance evaluation. Standard settings: 8 warmup iterations, 10
measurement iterations, 3 independent forks, 1-second iteration durations, fixed 1 GiB pre-touched
G1 heap, and retained JSON output. Trailing arguments append to the JMH invocation (e.g., pinning
`@Param` values). Decision runs reject outdated jars and prevent overwriting existing run
directories.

Empty or failed runs are rejected: JMH exits with code 0 even when benchmarks throw exceptions
(printing `<failure>` and producing an empty JSON array). The runner validates the result count and
console log before declaring success. On failure, it writes a `FAILED` marker, retains diagnostic
artifacts, and exits with a non-zero code. Patterns matching zero benchmarks and partial failures
are caught similarly.

Evaluate runs carefully: consider a run invalid if fewer than 3 fork series are present, required
metrics are missing, or 99.9% confidence error exceeds ~10% of the score. Always publish scores with
accompanying error bars.

Each run directory contains: JSON results, console log, full command invocation, JVM/host metadata,
benchmark jar checksum, build version, provider coordinates/class, Git status, and a binary patch
of tracked source state. Untracked files are recorded by SHA-256 in `untracked-files.txt` (or
embedded via `JMH_CAPTURE_UNTRACKED=content`, or omitted with `none`).

Results are stored under `results/` (ignored by Git). Archive accepted decision runs alongside
release or pull request artifacts.

Key environment variables: `JMH_FORKS`, `JMH_WARMUP_ITERATIONS`, `JMH_MEASUREMENT_ITERATIONS`,
`JMH_WARMUP_TIME`, `JMH_MEASUREMENT_TIME`, `JMH_JVM_ARGS`, `JMH_PROFILER`, and
`JMH_RESULTS_DIR`.

### Batch-Size and Duration Sensitivity

```bash
./jmh.sh sensitivity
./jmh.sh sensitivity '.*NameOps.name_depth_(varied|control)_batch_(1k|10k)'
```

The sensitivity runner executes multi-fork benchmarks at both 500 ms and 2 s iteration durations,
displaying comparison tables. A score that shifts significantly across durations or between `_1k`
and `_10k` batch variants indicates unamortized harness overhead or loop collapse rather than a
reliable baseline.

Batch-size comparisons require distinct methods with explicit `@OperationsPerInvocation` constants;
dynamic batch parameters cannot be correctly normalized by the annotation.

### Allocation Quantity and Attribution

```bash
./jmh.sh allocation '.*NameOps.name_depth_varied_batch_1k'
./jmh.sh allocation '.*StemOps.stem_emit_batch' -p depth=5
```

The allocation runner executes two complementary evaluations:

1. A 3-fork `-prof gc` decision run reporting quantitative normalized allocation
   (`gc.alloc.rate.norm` in B/op with error margin).
2. A 1-fork JFR diagnostic pass with maximum allocation sampling and detailed GC events, generating
   `allocation-by-site` reports.

The first establishes quantitative B/op. The second attributes allocations to specific call sites
(its timing is profiler-distorted and not a throughput metric). Claiming zero allocation requires
both zero B/op in the GC profiler and no application allocation sites in JFR.

Allocation benchmarks must construct fixtures at the trial or iteration level to prevent setup
overhead from contaminating profiler observations.

`CyclicOps` is the only class constructing fixtures inside the measured method body: each cycle
terminates via a stateful `limit(N)` fiber spent after one cycle, preventing conduit reuse across
invocations. Fixture construction accounts for 0.207 B/op (10k ops) and 0.021 B/op (100k ops),
scaling linearly (~2 KB fixed per invocation). Allocation reports reflect the cycle operation plus
this fixed fixture overhead.

## Writing a Benchmark

### Measurement Boundary

Emission benchmarks await circuit completion by default. Benchmark names denote the measurement
boundary only when departing from this default:

| Boundary                       | Name               | Timed work                                         | Unit                                       |
|--------------------------------|--------------------|----------------------------------------------------|--------------------------------------------|
| Complete batch drain (default) | `_batch`           | N admissions followed by one `await()`             | ns/source admission, end-to-end throughput |
| Caller admission               | `_admission_batch` | Admission only; drain occurs outside primary timer | ns/source admission                        |

`PipeOps.async_emit_batch` and `async_emit_admission_batch` illustrate this distinction.
`_admission_batch` avoids in-body awaiting and declares a `TerminalVerification` parameter, which
drains and validates after the timer stops. Because GC profilers observe the entire iteration
teardown, admission benchmark allocation remains end-to-end.

Non-emission benchmarks that do not await (e.g., signal map/set lookups, state iteration, and pool
lookups) admit no circuit work. Where `drain` appears in a name, it represents the domain operation
under test (e.g., `Basin.drain(Pipe)` in `BasinOps.basin_burst_then_drain_batch`).

**Awaiting per operation is strictly prohibited.** Awaiting after each individual operation measures
the ~8 µs caller-to-worker thread rendezvous rather than the nanosecond-scale operation. To measure
operation cost, batch emissions against a single trailing drain. To measure latency within a single
traversal, use the API-level `Pulse` construct.

### Batch Size and Normalization

Batched emission benchmarks standardize on `BATCH_SIZE = 10000`. An unsuffixed `_batch` denotes this
standard normalization. Benchmarks requiring different scales explicitly include the scale in the
method name (e.g., `NameOps.name_depth_*_batch_1k`, `CyclicOps.cyclic_emit_deep_100k`).

**Batching amortizes the await rendezvous.** At 10,000 operations, an 8 µs drain contributes ~0.8
ns/op to an 11 ns score; at 1,000 operations it contributes 8 ns/op (dominating the score); at 100
operations it accounts for ~80 ns/op. Benchmarks dominated by drain overhead are invalid.

`@OperationsPerInvocation` must reflect the logical unit (source admissions, terminal deliveries, or
protocol steps), documented whenever it diverges from one loop iteration. Fan-out benchmarks
(`MirrorOps`, `PipeOps`) normalize per source admission while verifying full terminal delivery
counts.

### Completion Evidence

Completion evidence must not inflate unbatched benchmarks. Invocation-level teardown fixtures
declared on a benchmark class apply to *all* methods in that class, introducing substantial overhead
to nanosecond-scale methods (e.g., inflating a 1.2 ns lookup to 71.9 ns).

Verification is encapsulated in `io.humainary.perfkit.jmh.TerminalVerification`, a `Scope.Thread`
state declared as a method parameter:

```java
@Benchmark
@OperationsPerInvocation(BATCH_SIZE)
public void async_emit_batch(
    final TerminalVerification verification
) {
  verification.expect(deliveries, BATCH_SIZE);
  ...
  circuit.await();
}
```

Available verification methods:

- `expect(tally, count)`: exact delivery count with in-body drain.
- `expect(tally, count, circuit)`: exact count with post-timer drain.
- `between(tally, least, most)` / `between(tally, least, most, circuit)`: range bounds for
  non-deterministic or processing-time operators (e.g., `chance`, `every(Duration)`, `heartbeat`).
- `drainAfter(circuit)`: drains circuit outside the timer when no output is counted.

Deliveries are recorded in `io.humainary.perfkit.jmh.Tally` rather than on benchmark state fields,
keeping circuit-thread writes isolated from caller threads.

When operations execute worker callbacks, count increments belong **inside the callback** (e.g.,
`PortOps` counting inside `update` transformations).

**Documented exceptions without terminal verification:**

| Row                            | Terminal                          | Why it is unverified                                                                                    |
|--------------------------------|-----------------------------------|---------------------------------------------------------------------------------------------------------|
| `BasinOps.baseline_emit_batch` | conduit with no subscriber        | nothing registered to count, by construction                                                            |
| `PipeOps.empty_emit_batch`     | no-op pipe                        | nothing registered to count, by construction                                                            |
| `BasinOps.basin_emit_batch`    | basin ring contents               | counting means draining, which is what `basin_burst_then_drain_batch` measures separately               |
| `CellOps.update_cell_batch`    | cell value, returned and consumed | the value escapes to JMH, but an escape is not a count; a count needs a receptor the cell does not have |
| `PortOps.replace_batch`        | none                              | `Port` is write-only by capability design — there is no read                                            |

Do not resolve unverified rows by appending artificial publish or drain steps to the measured
method; that creates composite benchmarks whereas the suite isolates individual processing stages.

### Stateful Phase Profiles

Operators whose cost varies by execution branch must maintain a fixed phase across invocations
rather than allowing branch mixes to drift. Define distinct benchmark methods for each phase (e.g.,
`FiberOps.streak_fire_batch`, `streak_miss_batch`, and `streak_match_batch`).

Avoid `@Param` sweeps over operational phases, as single-shot sweeps yield high error margins and
widen summary tables across all benchmarks.

### Recipe Field Storage for Attachment Benchmarks

Attachment benchmarks (`pipe_create_*`) measure `recipe.pipe(target)`. Recipes are constructed
during trial setup and stored in fields:

- In production, callers attach pre-constructed recipes to runtime targets.
- Storing recipes in fields prevents the JIT from inlining and dead-code-eliminating the attachment
  dispatch, ensuring realistic measurements.

### Preventing Dead-Code Elimination

Consuming a return value via JMH protects single-invocation benchmarks but does not guarantee that
every iteration of an inner loop executes. For batched workloads:

- Use runtime-populated, varying input arrays.
- Aggregate results or consume each iteration output via a `Blackhole`.
- Pair lookups with dedicated loop-and-consume controls to isolate loop overhead from operation
  cost.

### Measurement Safety Checklist

A valid benchmark satisfies:

1. **Active execution**: Verified via runtime-varying inputs and observable result sinks.
2. **Accurate operation count**: `@OperationsPerInvocation` precisely reflects work timed within the
   measurement boundary.
3. **Explicit boundary semantics**: Clear distinction between caller admission and batch drain.
4. **Verified completion**: Terminal delivery count validated via `TerminalVerification` (or
   explicitly listed as an unverified exception).
5. **Clean allocation profiling**: Fixture allocations excluded from `-prof gc` measurement
   boundaries.

### Benchmark Shapes

- **Hot-path**: Uses `@Setup(Level.Iteration)` to reuse circuit resources, measuring active circuits
  without setup interference.
- **Batched**: Uses `@OperationsPerInvocation(BATCH_SIZE)` to measure amortized per-operation
  throughput for sub-10 ns operations.
- **Single-operation**: Measures individual operations (e.g., materialization, state transitions)
  where batching would artificially alter the workload.

## Reading a Result

### What a Published Row Must Earn

A row belongs in a published table only when its score is directly attributable to the operation it
names. Four failure modes disqualify a row:

**The await floor.** Awaiting once per operation reports the ~8 µs rendezvous floor plus residual
variance. Multi-stage benchmarks mask this behind plausible numbers (e.g., a
create/bridge/emit/close sequence awaiting twice simply reports ~16 µs of rendezvous overhead).
Always measure distinct stages rather than compound sequences.

**Error comparable to score.** Results with confidence errors exceeding ~10% of the score are
rejected. When underlying variance is structural (e.g., virtual-thread startup), additional
iterations will not achieve convergence.

**Diagnostic count rather than rate.** Benchmarks measuring the duration of a fixed observation
window with results reported via `@AuxCounters` belong in diagnostic documentation rather than
standard throughput tables.

**Name outrunning fixture.** A benchmark whose score measures a real construct, but not what its
name advertises, produces plausible but misleading numbers. Two historical examples illustrate this:

- `PipelineOps` previously reported composed-flow rows without actually including a `Flow` in its
  fixture.
- `StemOps.stem_emit_batch` originally created its conduit with `Routing.PIPE` (the two-argument
  overload), under which parent chains are not traversed; leaf emissions reached only a single
  receptor, resulting in flat scores across depth parameter sweeps. Supplying `Routing.STEM`
  correctly engaged the parent-chain walk.

Both issues manifest as a benchmark **sitting on its class baseline**. When evaluating benchmarks
that compare equivalent mechanisms, verify that their expected terminal counts are identical (e.g.,
both stem and subscriber routing verify `BATCH_SIZE * depth`).

### Comparability Across Classes

Per-operation scores are comparable only when normalized over the same batch size. Batched emissions
amortize a single `circuit.await()` over their loop, so identical operations yield ~22 ns/op at
1,000 operations versus ~11 ns/op at 10,000 operations due to harness amortization differences.

`CyclicOps.cyclic_emit_deep_100k` illustrates this principle: running a 10x deeper cascade
normalized over 100,000 operations incurs a drain overhead of only 0.08 ns/op (versus 0.8 ns/op in
shallow forms), appearing ~0.7 ns faster purely due to normalization. Once adjusted for
normalization, the scores align, confirming linear scaling.

### Detection Threshold and Error Bars

Smoke-level runs serve as inventory checks, not regression detectors: roughly 75% of rows carry
errors exceeding 10% of their score. Such runs cannot reliably detect single-nanosecond shifts on an
11 ns emission.

Near the bottom of the measurement range (< 2 ns), relative percentage thresholds become less
informative, as absolute errors of a few tenths of a nanosecond represent harness resolution limits
rather than benchmark flaws.

Use the `core` suite at decision settings to evaluate broad performance changes.

### Structural vs. Statistical Variance

A wide error bar in a smoke run is an indicator to re-evaluate at decision settings rather than
redesign immediately. Running multi-fork decision tests separates rows into two categories:

- **Statistical variance resolved by configuration**: Pooled lookups in `BankOps`/`ConduitOps`,
  fan-out in `PipeOps`, and window traversals tighten from 20–40% error down to 3–7% with proper
  fork/iteration settings.
- **Structural variance inherent to the operation**: Processing-time operators
  (`FiberOps.every_duration_batch`, `heartbeat_duplicate_batch`) exhibit survivor counts dependent
  on wall-clock iteration duration, resulting in ~11–13% variance across forks that cannot converge
  through additional iterations.

Rely on active benchmark runs rather than hard-coded figures in documentation, as absolute numbers
reflect specific hardware and environments.

### Topology

Emission cost depends on topology. `PipeOps` measures chaining and fan-out against the single-pipe
baseline:

```bash
./jmh.sh run 'PipeOps.async_emit_(batch|chained_batch|fanout_batch)'
```

- `async_emit_batch`: Baseline with one source admission and one receptor.
- `async_emit_chained_batch`: Forwards through an intermediate pipe, measuring cascading
  transit-queue emissions.
- `async_emit_fanout_batch`: Dispatches one admission to three receptors, measuring inlet iteration
  and receptor dispatch.

All three normalize per **source admission**. Fan-out scores measure dispatch cost per incoming
admission while validating three terminal deliveries in teardown.

`static_fanout_emit_batch` measures a distinct topology: a conduit inlet invokes registered
receptors inline on the circuit worker (one admission, one queue traversal), whereas
`circuit.pipe(List)` holds pipes that each queue emissions separately (one ingress traversal plus
one transit traversal per target). The performance difference reflects this re-queueing cost.

`StemOps` sweeps hierarchy `depth` using `@Param`, comparing synchronous parent-chain walks
(`Routing.STEM`, ~2 ns per ancestor hop) against subscriber-linked outlet chains (~10 ns per hop).
At `depth=1`, both perform identical work and align within error margins (~13.3 ns).

### Serventis Signals

Serventis emission combines cached signal lookup, source admission, circuit processing, and
downstream delivery before `await()` completes.

Every sign within an instrument domain shares the same underlying conduit path; benchmarking every
sign or domain independently redundantly exercises the same code path. **A domain warrants a
benchmark class only when it introduces processing above the emit path.** `QueueOps` represents the
base emit path for all domains, while `ScorecardOps`, sequencers (`SequencerOps`,
`BracketSequencerOps`), and lookup structures (`SignMapOps`, `SignalMapOps`, `SignalSetOps`) measure
higher-level semantic transformations.

## Publishing and Comparing

### Measurement Status

Published results must originate from retained decision runs accompanied by their `metadata.txt`.
Archive the complete run directory rather than extracting isolated summary rows.

### Repository Artifacts and Provenance

Retained run directories under `results/` contain detailed environment provenance: `metadata.txt`
captures host architecture, CPU/memory configuration, JDK build, and Git revision, while
`source-state.patch` records working tree modifications. Because `results/` is excluded from version
control, publish selected benchmark artifacts deliberately when establishing a new baseline.

The `perfkit` directory is entirely self-contained: it includes its own Maven wrapper, declares
dependencies by Maven coordinates, and builds independently.

### Rendering Tables

`./jmh.sh table` formats decision-run JSON, a JMH console log, a table it previously rendered, or
piped input into a fixed-width table of score, error margin, and implied throughput:

```bash
./jmh.sh table results/<run>/results.json   # decision-run JSON (preferred)
./jmh.sh table run.log                      # JMH console output
./jmh.sh table results.out                  # a table this command rendered
./jmh.sh run PipeOps | ./jmh.sh table       # piped
```

Parameters declared via `@Param` are formatted directly into benchmark names as `[key=value]`,
displaying normalized score, error margin, and implied throughput (`ops/sec`). Rows appear in run
order, and column widths are computed over the rows present, so an excerpt renders narrower than the
run it was taken from. A rate is derived from the rounded score and only for average-time rows: it
cannot be read off a single-shot duration or a secondary count metric, so those rows carry their raw
unit instead and print `-` for `ops/sec`.

An excerpt of such a table:

```text
Benchmark                              ns/op     ±        ops/sec
NameOps.name_depth_control_batch_10k   0.286  0.045  3,496,503,497
NameOps.name_depth_varied_batch_10k    0.482  0.089  2,074,688,797
PipeMapOps.baseline_plain_batch       10.527  0.515     94,993,825
PipeOps.async_emit_batch              11.171  0.987     89,517,501
PipeOps.empty_emit_batch              10.366  0.799     96,469,226
StemOps.stem_emit_batch[depth=1]      13.351  0.930     74,900,756
StemOps.stem_emit_batch[depth=3]      19.340  1.800     51,706,308
StemOps.stem_emit_batch[depth=10]     31.768  1.086     31,478,217
```

**These numbers illustrate the format; they are not a reference.** They come from one non-contiguous
slice of one smoke run on one machine — Apple M4, JDK 26.0.1,
`io.humainary.substrates:humainary-substrates-spi-alpha:3.0.0`, 2026-08-16 — whose error bars are
correspondingly wide, and they will not reproduce on other hardware or against another provider. The
provider named is the one this suite is developed against; it is not distributed here, and no
provider is. Read current figures from your own decision run, per
[Measurement Status](#measurement-status).

What the rows encode is the durable part: a varied lookup sits above its own loop-and-consume
control, emission rows share the band the per-class baselines establish, and one `@Param` sweep is
folded into three names. [Reading a Result](#reading-a-result) covers how to read them.

### Comparing Retained Runs

```bash
./jmh.sh compare old.json new.json
```

Benchmarks are matched by name, reporting additions, deletions, and relative score changes. A
performance difference is flagged as significant only when it exceeds the quadrature sum of both
error margins (`sqrt(e_old² + e_new²)`). Differences within the combined error margin are marked
with `~` (noise band). Rows missing error bars on either side display `?`.

Use `--all` to display all compared rows rather than only those with significant deltas.

## Benchmark Configuration

All benchmark classes declare standard smoke-test defaults:

```java
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
```

This configuration provides quick inventory verification. For rigorous evaluation, use
`./jmh.sh decision`.

## Contributing Benchmarks

### Where a Benchmark Lives

Every class in this suite is published under `io.humainary.perfkit`, the groupId that ships it —
never under the namespace of an API it measures, which belongs to another artifact and another
repository. Below that root, packages record what a benchmark measures:

```text
io.humainary.perfkit.jmh              harness shared by both trees (Tally, TerminalVerification)
io.humainary.perfkit.jmh.substrates   Substrates API benchmarks, flat
io.humainary.perfkit.jmh.serventis.*  Serventis benchmarks, mirroring the API subpath
```

Two rules follow.

**A package names the subject, not the imports.** `ScorecardOps` measures `sdk.Scorecards` and sits
in `.serventis.sdk`, though it imports `opt.pool.Resources` for sign data; the sequencer benchmarks
sit in `.serventis.sdk.meta` while importing `opt.sync.Locks` the same way. Those imports supply
input, not subject. A benchmark changes package only when what it measures moves.

**The mirror starts below the API root package.** Serventis spreads its API over `sdk`, `sdk.meta`,
and seven `opt` domains, so mirroring that subpath records which layer a benchmark measures and is
kept. Substrates publishes a single API package, `io.humainary.substrates.api`, so mirroring it
would add one `.api` segment distinguishing nothing; those benchmarks stay flat. Nest only where the
API being measured is itself nested.

Moving a benchmark between packages needs no change outside the source tree, the root included:
named suites match on `.*Class\.method`, and `jmh-table.sh` identifies a rendered row by class and
method with the package discarded.

That last point carries a constraint on naming: **benchmark class names must be unique across the
suite.** Uniqueness is what allows a row name to omit the package, and omitting the package is what
makes two runs comparable at all — JMH abbreviates console names against the inventory of the run
that produced them, so a package-bearing row name would spell the same benchmark one way in a
focused run and another in a broad one, and `compare` would read the difference as a row removed
and a row added rather than as a delta. The same uniqueness is what lets `./jmh.sh run PipeOps`
name a benchmark without qualification.

### Measurement Rules

1. **Explicit timing boundaries**: Clearly distinguish between caller admission and full circuit
   drain.
2. **Validate batch sensitivity**: Verify that per-operation scores remain stable across 500 ms and
   2 s durations.
3. **Standard batch size**: Default to `BATCH_SIZE = 10000`; include non-standard scales directly in
   method names.
4. **Account for await overhead**: Ensure that the ~8 µs drain represents a negligible fraction of
   the total batch duration.
5. **Benchmark paths, not enum values**: Test distinct execution paths rather than individual sign
   constants.
6. **Prevent loop elimination**: Use runtime-varying input arrays and observable result
   accumulators.
7. **Isolate completion evidence**: Declare `TerminalVerification` as a method parameter rather than
   adding class-level teardown fixtures.
8. **Exclude fixture allocation**: Initialize benchmark state at trial or iteration level to keep
   `-prof gc` results accurate.
9. **Focus on recurring operations**: Avoid benchmarking one-time wiring and setup operations.
10. **Use `@Param` sparingly**: Apply parameter sweeps only when evaluating scaling curves (e.g.,
    hierarchy depth).
11. **Verify fixtures match intent**: Confirm that fixtures construct the exact topology described
    by the benchmark name.
12. **Preserve run provenance**: Retain full JSON outputs and execution metadata.

## Example: Comparing Benchmark Types

Evaluating the performance profile of a conduit:

```bash
# 1. Measure single-operation pipe materialization
./jmh.sh run PipeOps.pipe_create

# 2. Measure amortized name lookup against its control
./jmh.sh run 'ConduitOps.get_varied_(control_)?batch'

# 3. Measure emission throughput (admission vs. full drain)
./jmh.sh run 'PipeOps.async_emit_(batch|admission_batch)'
```

This workflow provides three distinct insights: pipe materialization cost per subject, amortized
cached lookup cost, and steady-state emission throughput.

## See Also

- `src/main/java/` — Benchmark source implementations
- [Substrates API](https://github.com/humainary-io/substrates-api-java) — Substrates specification and Java projection
- [Serventis API](https://github.com/humainary-io/serventis-api-java) — Serventis architectural overview and semantic ascent
