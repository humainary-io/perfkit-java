# Humainary Perfkit

JMH benchmark suite for Java providers of the Humainary Substrates and Serventis APIs.

Perfkit does not build or install the APIs or the provider. Those artifacts must be available to
Maven before building the suite. Perfkit measures a provider's steady-state cost: emission and its
boundaries, operator chains, pooled lookup, name and state access, and the semantic ascent layer.

## Preconditions

Verify all of the following before building the suite:

1. **Java 26 is active.** `JAVA_HOME` and `java --version` must select JDK 26.
2. **Both public APIs are Maven-resolvable.** Default dependencies:
    - `io.humainary.substrates:humainary-substrates-api:3.0.1`
    - `io.humainary.serventis:humainary-serventis-api:3.0.1`

   Perfkit itself does not depend on `io.humainary.specs:humainary-specs-api`. If you are building
   the APIs yourself at a revision that has not been published, install that artifact first: both
   APIs compile against its traceability annotations at `provided` scope.
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
wrapper downloads Maven 3.9.16 if not already cached and verifies its SHA-256. The supported runner
interface requires Bash and standard Unix tools; use macOS, Linux, WSL, or Git Bash. Native Windows
can compile with `mvnw.cmd`, but the build/run/provenance workflow below still requires Bash.
Rendering result tables from JSON requires `jq`; without it, the runner falls back to reading the
console log.

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

The suite defaults to Substrates API `3.0.1` and Serventis API `3.0.1`, as declared in
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

## Interpret Results

- Always report scores **with their error margins**, never in isolation.
- Treat results as unreliable when fewer than three fork series are present, or when the 99.9%
  confidence error exceeds ~10% of the score.
- When comparing runs (`./jmh.sh compare old.json new.json`), a difference is considered significant
  only when it exceeds the combined error of both runs.

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
