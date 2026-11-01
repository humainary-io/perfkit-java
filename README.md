# Humainary Perfkit

JMH benchmark suite for Java providers of the Humainary Substrates and Serventis APIs.

Perfkit does not build or install the APIs or the provider. Those artifacts must be available to
Maven before building the suite. Perfkit measures a provider's steady-state cost: emission and its
boundaries, operator chains, pooled lookup, name and state access, and the semantic ascent layer.

## Preconditions

Verify all of the following before building the suite:

1. **Java 27 is active.** `JAVA_HOME` and `java --version` must select JDK 27, or `JMH_JAVA_HOME`
   must name one.
2. **Both public APIs are Maven-resolvable.** Default dependencies:
    - `io.humainary.substrates:humainary-substrates-api:3.15.3`
    - `io.humainary.serventis:humainary-serventis-api:3.15.3`

   If you build the APIs yourself at an unpublished revision, install
   `io.humainary.specs:humainary-specs-api` first: both APIs compile against its traceability
   annotations at `provided` scope. Perfkit itself does not depend on it.
3. **The provider artifact is Maven-resolvable.** You must know its Maven `groupId`, `artifactId`,
   and `version`. Configure remote repositories and credentials in Maven settings, or install
   unpublished artifacts into the local Maven repository.
4. **The provider is discoverable at runtime.** The provider class must extend
   `io.humainary.substrates.spi.CortexProvider`, declare a public no-argument constructor, and be
   selected via:
    - `META-INF/services/io.humainary.substrates.spi.CortexProvider` in the provider artifact
      containing exactly one provider class (recommended); or
    - `SPI_PROVIDER=<provider-class>` passed on the run. Runners forward this to the forked JVM as
      `-Dio.humainary.substrates.spi.provider` (passing `-D` directly on the command line is
      rejected by JMH's option parser).
5. **Only the intended provider is selected.** If multiple providers are discoverable via
   `ServiceLoader`, select one explicitly with `SPI_PROVIDER`.

The Maven wrapper is included; a system Maven installation is not required. On first use, the
wrapper downloads Maven 3.10.0 if not already cached and verifies its SHA-256. The supported runner
interface requires Bash and standard Unix tools; use macOS, Linux, WSL, or Git Bash. Native Windows
can compile with `mvnw.cmd`, but the build/run/provenance workflow below still requires Bash.
Rendering result tables from JSON requires `jq`; without it, `table` and `compare` refuse JSON and
ask for the run's console log (`run.log` beside the JSON), which they read without it.

## Install Measured Artifacts

The normal external-checkout workflow installs the projects into the local Maven repository before
building Perfkit:

1. Clone [Substrates API](https://github.com/humainary-io/substrates-api-java) and run
   `./mvnw install` in that checkout.
2. Clone [Serventis API](https://github.com/humainary-io/serventis-api-java) and run
   `./mvnw install` in that checkout.
3. Build and install the provider to measure, using the provider's own instructions.
4. Configure that provider's coordinates in Perfkit and build the suite.

Use `mvnw.cmd install` for the two installation steps in a native Windows shell. Install compatible
API and provider revisions; Perfkit resolves only their artifacts and never reads those sibling
source checkouts.

Every step above installs into `~/.m2/repository`. A checkout that sets `MAVEN_REPO_LOCAL` to a
different repository must install into that one as well —
`./mvnw install -Dmaven.repo.local="$MAVEN_REPO_LOCAL"` — or Perfkit resolves from a repository
the artifacts were never installed into.

## Configure

Settings come from the environment. `jmh.env`, beside the runners, is how that environment is
configured once per checkout instead of once per command:

```sh
./jmh.sh env --init     # write jmh.env from the template, then edit it
./jmh.sh env            # what the next command will use, and where each value came from
```

Every runner loads `jmh.env` before doing anything else, so a configured checkout builds and runs
with bare commands. A variable exported in the calling shell always wins over the file, so a one-off
stays a one-off (`SPI_VERSION=1.1.0 ./jmh.sh build`), and `JMH_ENV=<path>` selects a different file
to keep several configurations side by side. `jmh.env` is untracked: it names the artifacts on one
machine, not a property of the suite.

| Setting                                    | Purpose                                             |
|--------------------------------------------|-----------------------------------------------------|
| `SPI_GROUP`, `SPI_ARTIFACT`, `SPI_VERSION` | Provider to measure; supplied together, at build    |
| `SPI_PROVIDER`                             | Provider class, when ServiceLoader is ambiguous     |
| `SUBSTRATES_API_VERSION`                   | API measured through; defaults to the pom           |
| `SERVENTIS_API_VERSION`                    | API measured through; defaults to the pom           |
| `MAVEN_REPO_LOCAL`                         | Maven local repository, when not `~/.m2/repository` |
| `JMH_JAVA_HOME`                            | JDK for the build and the run                       |

`jmh.env` cannot choose the JDK through `JAVA_HOME` or `PATH`: nearly every shell already sets both,
and the shell wins. Set `JMH_JAVA_HOME` there instead. Any `jmh.env` assignment the shell overrides
is named on stderr when a command starts.

## Build the Suite

With `jmh.env` configured, the whole command is:

```sh
./jmh.sh build
```

Equivalently, supplying the provider coordinates directly:

```sh
SPI_GROUP=com.example \
SPI_ARTIFACT=example-substrates-provider \
SPI_VERSION=1.0.0 \
  ./jmh.sh build
```

Supplying `SPI_ARTIFACT` activates the provider dependency, requiring `SPI_GROUP` and `SPI_VERSION`
as well. A build without coordinates produces a suite with no provider; benchmarks can be listed but
not run.

This phase compiles the benchmarks and assembles the jar, and does no more. The APIs and the
provider are resolved artifacts, so all three must already be installed — `./jmh.sh env` reports
whether the provider artifact is present.

**Coordinates apply at build time only.** The assembled jar packages the provider, and subsequent
commands read the provider from the jar's build metadata. Retaining `SPI_*` settings that disagree
with the jar causes an error rather than an override, preventing runs that measure one provider
while reporting another. In contrast, `SPI_PROVIDER` selects at runtime among providers already
packaged in the jar.

The suite defaults to Substrates API `3.15.3` and Serventis API `3.15.3`, as declared in
`pom.xml`. Override either with `SUBSTRATES_API_VERSION` and `SERVENTIS_API_VERSION`; these
are independent of this suite's own version, which is a literal in `pom.xml`.

## Run Benchmarks

```sh
./jmh.sh list                                  # list benchmarks in current jar
./jmh.sh run PipeOps.async_emit_batch          # quick smoke run
./jmh.sh decision core                         # fixed settings, retained artifacts

# Explicit provider selection when ServiceLoader finds multiple providers:
SPI_PROVIDER=com.example.ExampleCortexProvider ./jmh.sh run PipeOps.async_emit_batch
```

Use `decision` for actionable results: it enforces fixed forks, iterations, and JVM settings,
rejects jars older than this project's source or build configuration, or than the API and provider
artifacts they were built against, and records raw JSON, JVM/host metadata, and provider coordinates
under `results/`.

### Signal monitor demonstration

The signal monitor turns a deterministic sample recording into 64-sample energy/peak measurements,
then uses two `Flow.map` plus seeded `Fiber.diff` branches to retain activity and peak transitions.
After building with a provider, run its explicit correctness mode or the readable demonstration:

```sh
java -cp target/humainary-perfkit-3.15.3-jar-with-dependencies.jar \
  io.humainary.perfkit.demo.SignalMonitor --verify

java -cp target/humainary-perfkit-3.15.3-jar-with-dependencies.jar \
  io.humainary.perfkit.demo.SignalMonitor

./jmh.sh run '.*SignalMonitorOps.*'
```

The four benchmark rows compare matched direct and flow/fiber execution at 16,384 and 65,536
samples. Scores are normalized to ns/input sample; use
`./jmh.sh allocation '.*SignalMonitorOps.*'` for bytes/input sample and `decision` for retained,
multi-fork evidence.

## Showcase

`./jmh.sh showcase` runs a finite workload that Substrates drives itself, through the public API
alone. Tokens are carried through a population of circuits: each circuit makes a burst of
deliveries with a token, the first arriving from the previous circuit and the rest from itself,
then passes it to the next circuit in its chain. The main thread only builds the chains, seeds the
tokens, waits for them to finish, checks every circuit's count, and reports. One scope holds every
circuit and closes them all at the end.

```sh
./jmh.sh showcase                                   # a million circuits for five minutes, a token per processor
./jmh.sh showcase --circuits 10000,100000,1000000   # three populations, one run after another
./jmh.sh showcase --circuits 8 --tokens 8           # every circuit feeding itself
./jmh.sh showcase --minutes 1                       # a shorter run
```

| Option       | Sets                                                     | Default                 |
|--------------|----------------------------------------------------------|-------------------------|
| `--circuits` | The population, in whole numbers                         | 1,000,000               |
| `--burst`    | Deliveries each circuit makes with a token, per round    | 10⁹ × tokens ÷ circuits |
| `--tokens`   | Chains progressing at once; the circuits split evenly    | One per processor       |
| `--minutes`  | Time the measured rounds run, after two warmup rounds    | 5                       |
| `--repeat`   | Runs per combination, one after another                  | 1                       |
| `--observe`  | `on` records collections and samples the host            | `off`                   |

The first three take comma-separated lists. Every combination runs `--repeat` times, one run
after another, each in a fresh JVM that holds the whole population. With as many tokens as
circuits, each circuit feeds itself and no token changes circuit. A round is `circuits × burst`
deliveries and `circuits − tokens` handoffs between circuits. The default burst gives each token
a billion deliveries a round, which one processor carries in a few seconds, so a round lasts about
as long whatever the population or the machine. Measured rounds repeat until they have run for
`--minutes`; on a ten-core laptop the default five minutes come to nearly a trillion deliveries.
Two warmup rounds are reported but not counted. The first starts every circuit's worker and
compiles the code, and the second takes the allocation that follows the first one's collections.

Each round reports its time, deliveries and handoffs per second, the share of processors busy,
processor time per delivery, bytes allocated per circuit visit, and collections. A round visits
every circuit once, whether its token comes from the seed or from another circuit. The workload
allocates nothing of its own, so a visit's allocation is what waking a circuit and letting it go
idle again costs. Between collections that is one small object, the task the JDK schedules each wake
with. After a collection the JDK cannot reuse a parked virtual thread's stack chunk, so each
circuit's next idle transition allocates a new one of a few hundred bytes. A round during or just
after a collection shows more bytes per visit for that reason. Where the VM cannot count allocation,
the column shows a dash. The run also reports the heap each circuit holds after the rounds.

The VM is G1 with the heap fixed and pre-touched: half a gibibyte plus 8 KiB a circuit, capped at
three quarters of the memory the machine or its container allows. A circuit holds about 2 KiB, and
each collection makes every parked circuit allocate a new stack chunk. Results measured before
2026-10-31 used 4 KiB a circuit, at which the old generation could fill with dead chunks until
collections came every few seconds, costing up to a tenth of the throughput, so compare them with
`SHOWCASE_HEAP` set to match. `SHOWCASE_HEAP` fixes the heap, and
`SHOWCASE_JVM_ARGS` replaces the VM flags. The runner records the machine before it starts:
processors, cores, sockets, NUMA nodes, SMT, a container's limits, and the machine type on
Compute Engine. Results go to `results/<timestamp>-showcase/`, with each run's console log and
summary line.

`--observe on` adds, beside each run's console log, a `.gc` log of every collection with its pause
and processor time and, on Linux, one-second samples of the host: `.vmstat` for user, system and
stolen processor time, and `.mpstat` for each processor when `mpstat` is installed. Stolen time is
what a virtual machine's hypervisor gave to others, which the busy column cannot tell from the run's
own work. Observing costs something, so it is off by default; pair a run with it against one
without on the same machine before reading figures from either.

## Interpret Results

- Always report scores **with their error margins**, never in isolation.
- Treat results as unreliable when fewer than three fork series are present, or when the 99.9%
  confidence error exceeds ~10% of the score.
- When comparing runs (`./jmh.sh compare old.json new.json`), a difference is considered significant
  only when it exceeds the combined error of both runs. The comparison warns when the two runs were
  measured on different systems, since a difference may then come from the system.

See `BENCHMARKS.md` for the full measurement and publication workflow: benchmark scope, guidelines
for authoring benchmarks, and guidance on interpreting results.

## Distribution

This project distributes **source code**. The assembled `-jar-with-dependencies` is a local build
artifact, not a release artifact: it bundles JMH (GPLv2 with Classpath Exception) and the selected
provider. Build it locally; do not publish the assembled jar.

Source mirrors and archives contain tracked files only. Exclude `jmh.env`, `results/`, `target/`,
and local `*.out` files; all are already covered by `.gitignore`.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
