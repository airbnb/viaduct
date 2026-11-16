# Selection Forest Creation Benchmark

`SelectionForestCreationBenchmark` measures Runtime2's current conversion of an already validated GraphQL operation into a `SelectionForest`. GraphQL parsing, validation, schema creation, variable coercion, and construction of the predecoded inputs happen in JMH trial setup and are outside the measured methods. The full conversion benchmark starts with the validated operation AST and coerced variables, matching `Runtime2ExecutionStrategy`'s preparation boundary.

This stage benchmark is retained as an AST reference, not the production ND path. `decodeOperation` builds the source-world `SpecSelection` control model, and `createMaterializeForest` flattens that model into the unified `SelectionForest`. `createConstructionForest` now returns the same graph: construction and projection no longer require a conversion. `createSelectionForest` measures the complete eager AST reference. The [historical two-forest results](../evidence/profiles/2026-11-15-selection-forest-creation/README.md) apply to their archived source revision; rerunning this class after unification does not reproduce that baseline. Use the [ND migration comparison](normalized-document-benchmark.md#migration-comparison) for current before/after measurements.

## Fixtures

The checked-in compressed SDL and query resources come from graphql-java commit `69c7bfbef920e5b95b7c295b60ef0652523eef3d`, under `src/test/resources`. Their original SHA-256 values are:

| Upstream file | SHA-256 |
| --- | --- |
| `large-schema-2.graphqls` | `d7e79df55755ae1d18fa1b953a8d9984bd54a9b881f5e2fd84bd1b74668cc512` |
| `large-schema-2-query.graphql` | `410ccd6f430e74ca3385245b2a7a09a374f1c27a65c5d03a46dc547457397b55` |
| `large-schema-4.graphqls` | `c6a1e0f3f8314756e0ef3f7f738a7db103aba57e54b3227326327bad233e9e7e` |
| `large-schema-4-query.graphql` | `13fa4d4077a6e37ea88b1245cee78216b4467bd81fab9f335d77a055a62ccec9` |

`schema2-small` uses schema 2 and its upstream query. `schema4-small` uses schema 4 and the derived two-field query `query { field29531 { field30371 { __typename } } }`. `schema4-full` uses schema 4 and its upstream stress query. Graphql-java counts 259,832 field occurrences after fragment expansion, above its default 100,000-occurrence validation limit; the query also exceeds the default 10,000 merged-field limit. Trial setup therefore validates it with `QueryComplexityLimits.NONE`. The timed conversion remains unchanged. The graphql-java fixture license is retained beside the resources.

## Bounded Hetzner run

Build the JMH jar from the repository root with `./gradlew :core:engine:runtime2:jmhJar --console=plain`, then transfer that jar and both scripts under `core/engine/runtime2/scripts` to the server. Run `run-selection-forest-creation-benchmark.sh JMH_JAR RESULT_DIRECTORY` on Hetzner; an optional third argument selects one fixture for a 32-minute subset run. The script takes the shared advisory lock, saves and restores the current CPU governors and update-service states, records Java and jar metadata, collects JMH GC allocation results, and writes raw JSON and logs.

There are four methods, three fixtures, and two forks, for 24 forks. Every fork schedules two 30-second warmup iterations and three 60-second measurement iterations. The total scheduled iteration time is exactly `4 × 3 × 2 × (2 × 30 + 3 × 60) = 5,760 seconds`, or 96 minutes: 24 minutes of warmup and 72 minutes of measurement. JVM startup, trial setup, fork teardown, and result reporting add wall-clock time. The runner enforces a 110-minute JMH deadline plus up to 30 seconds for forced termination. It does not include the separate jar build or transfer.

Treat each method and fixture as one workload, and report both time per operation and allocated bytes per operation. Compare the complete conversion with its components, then compare small and full queries on schema 4 to separate operation-size effects from schema-size effects. These are conversion costs, not end-to-end execution latency.
