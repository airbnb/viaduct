# Runtime2 Agent Guidance

Runtime2 is the alpha engine implementation in `core/engine/runtime2`. Start with [`README.md`](README.md) for its scope, source layout, resolver-family role, build boundary, and complete documentation map.

Use `runtime2` and [`runtime`](../runtime) to distinguish the two engine implementations. Runtime2's Engine API implementation is [`Engine2`](src/main/kotlin/viaduct/engine/runtime2/Engine2.kt). Its production field-resolution algorithm is [`Resolution`](src/main/kotlin/viaduct/engine/runtime2/resolution/Resolver.kt).

## Source And Architecture

- [`impldocs/architecture/principles.md`](impldocs/architecture/principles.md) defines the durable semantic and architectural rules.
- [`impldocs/architecture/execution-model.md`](impldocs/architecture/execution-model.md) defines the idealized source-world execution model.
- [`impldocs/architecture/model.md`](impldocs/architecture/model.md) defines semantic carriers, equality, construction, and factory-established invariants.
- [`impldocs/architecture/resolution.md`](impldocs/architecture/resolution.md) is the canonical description of production Resolution.
- [`impldocs/architecture/resolver-families.md`](impldocs/architecture/resolver-families.md) owns the maintained family boundaries, comparison grid, naming policy, and feature-development workflow.
- [`impldocs/architecture/access-checks.md`](impldocs/architecture/access-checks.md) owns checker vocabulary, demand, application identity, and enforcement semantics.
- [`impldocs/architecture/examples.md`](impldocs/architecture/examples.md) gives worked examples of demand closure, output projection, Query-OER sharing, and `@parent` constraints.

Production code belongs in `src/main`. The numbered reference implementations in [`src/support/kotlin/viaduct/engine/runtime2/resolvers`](src/support/kotlin/viaduct/engine/runtime2/resolvers), correctness machinery, generators, and neutral development support belong in the unpublished `src/support` source set. Reusable JUnit contracts belong in `src/test/fixtures`, concrete tests in `src/test/kotlin`, and benchmarks in `src/jmh`.

The numbered implementations are maintained architectural controls as well as a feature ladder, not production engine choices or a continuous sequence of versions. For a semantic feature, establish the compact behavior in the recursive depth-first family (resolver01–03), carry it through the explicit-task family (resolver06–08), then the coroutine family (resolver21–23), and finally production Resolution. Read the [family comparison and source links](impldocs/architecture/resolver-families.md#comparison-grid) before changing decomposition, shared framework boundaries, or family naming.

## Integration And Testing

- [`impldocs/integration/engine-api.md`](impldocs/integration/engine-api.md) defines the Engine API adapter, supported alpha surface, and current exclusions.
- [`impldocs/integration/feature-tests.md`](impldocs/integration/feature-tests.md) defines the production-derived feature-test comparison and its `TODO`, `N/A`, and `ALT` classifications.
- [`impldocs/testing/guide.md`](impldocs/testing/guide.md) owns day-to-day validation, replay-first debugging, failure classification, and counterexample handling.
- [`impldocs/testing/strategy.md`](impldocs/testing/strategy.md) defines the evidence supplied by contracts, correctness oracles, generated tests, exact witnesses, mutation tests, and directed stress tests.
- [`impldocs/testing/resolution.md`](impldocs/testing/resolution.md) owns production Resolution's focused, stress, concurrency, and campaign commands.
- [`impldocs/testing/property-tests.md`](impldocs/testing/property-tests.md) owns generated-world composition, profile resources, replay, shrinking, rounds, and campaigns.
- [`impldocs/testing/resolver-dsl.md`](impldocs/testing/resolver-dsl.md) defines deterministic schema-embedded resolver worlds and counterexamples.
- [`impldocs/testing/performance.md`](impldocs/testing/performance.md) owns maintained benchmark, profiling, corpus, and reporting practice.

Use [`impldocs/correctness/claims.md`](impldocs/correctness/claims.md) to find stable propositions and their scoped arguments. [`impldocs/correctness/inclusion-validation.md`](impldocs/correctness/inclusion-validation.md) records the present inclusion-validation guarantees and remaining design boundary. Documents under `impldocs/evidence` preserve research provenance and dated evidence; their historical commands, paths, and names are not current operating guidance and must not be mechanically modernized.

## Validation

Run Runtime2 Gradle commands from the repository root. A "full check" means exactly:

```sh
./gradlew :core:engine:runtime2:check
```

"Surgical tests" means narrower targets within `:core:engine:runtime2`; choose them from the testing guide or the production Resolution testing guide. Runtime2 tasks currently do not support Gradle configuration caching.

The vendored GraphQL specification has an independent opt-in rendering build. Read [`impldocs/graphql-spec/UPSTREAM.md`](impldocs/graphql-spec/UPSTREAM.md) before updating or rebuilding it.

## Standalone TLA Project

The `tla` directory is a standalone, disposable formal-modeling project. Unless a task explicitly asks for TLA work, ignore that directory: do not consult it as Runtime2 implementation documentation, update it alongside engine changes, or introduce references to it under `impldocs`.

## Documentation Practice

Runtime2 documentation describes the current alpha implementation declaratively. Preserve durable rationale in architecture, testing, correctness, or evidence documents instead of adding chronological project-status narratives.

Write each prose paragraph and list item in Markdown on one physical line; do not hard-wrap prose. Preserve structural line boundaries for headings, separate list items, tables, and fenced code blocks.

The nearest nested `AGENTS.md` adds package-specific navigation and constraints.
