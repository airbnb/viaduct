# Demo Applications

The demo applications in this directory serve as integration tests and usage examples for the Viaduct Gradle plugins. Each demo exercises a different framework integration (Ktor, Micronaut, Jetty, Spring, plain CLI) and Kotlin version.

## KSP Test Coverage

The demo apps collectively verify the KSP registry-extractor processor across the supported Kotlin and KSP version matrix:

| Demo App | Kotlin | KSP | Generation | Coverage goal |
|---|---|---|---|---|
| cli-starter | 1.9.24 | 1.9.24-1.0.20 | KSP1 | Oldest supported |
| jetty-starter | 2.0.21 | 2.0.21-1.0.28 | KSP1 | KSP1 on 2.0 |
| micronaut-starter | 2.1.20 | 2.1.20-1.0.32 | KSP1 | KSP1 on 2.1 |
| ktor-starter | 2.1.20 | 2.1.20-2.0.1 | KSP2 | KSP2 on 2.1 (same Kotlin, different KSP) |
| starwars | 2.2.21 | 2.2.21-2.0.5 | KSP2 | Latest |

Full transition to KSP2-only will occur after we deprecate Kotlin 1.9 support. Note that Kotlin 2.3+ uses a standalone KSP model (version-independent of the compiler), which will require a separate migration when we extend support past 2.2.

For more on KSP versioning see `ksp-versioning.md`.
