# Runtime2

Runtime2 is the alpha implementation of Viaduct's new engine. Its public contract is [`viaduct.engine.api.Engine`](../api/src/main/kotlin/viaduct/engine/api/Engine.kt): Runtime2 supplies the production resolution algorithm, schema and dispatcher adaptation, and GraphQL Java execution components that implement that contract. `StandardViaduct` selects Runtime2 when `ENGINE2_ENABLED` is enabled; the existing engine remains the default.

The production field-resolution implementation is [`Resolution`](src/main/kotlin/viaduct/engine/runtime2/resolution/Resolver.kt), in `src/main/kotlin/viaduct/engine/runtime2/resolution`. Its shared production framework is in the adjacent `resolution/framework` package.

## Alpha Scope

Runtime2 executes queries and ordered mutations with selective and non-selective field and node resolvers, field and type access checks, object- and Query-rooted required selections, resolver variables, root-field references, nested `ctx.query()` and `ctx.mutation()` execution, GraphQL completion, and incremental `@defer` delivery. Mutation namespace checkers and mutation resolver required selections remain excluded. The detailed supported and rejected surface is maintained in [Engine API integration](impldocs/integration/engine-api.md).

Major exclusions from the current Engine API integration include batching, mutation namespace checkers, subscriptions, custom scalars, `@stream`, and some resolver combinations documented with the integration boundary. Runtime2 reuses the production dispatcher bootstrap and service wiring behind an opt-in feature flag; that path is not yet the default.

## Source Layout

- [`src/main`](src/main) contains the production model, schema lowering, bootstrap, execution integration, Resolution, and the shared production resolution framework. The `viaduct.engine.runtime2.model` package deliberately uses stylized Kotlin as an executable semantic model rather than as a conventional object-oriented domain model. Only this source set is published.
- [`src/support`](src/support) contains Resolver01–23, correctness machinery, generators, benchmark support, and neutral development fixtures. It is development support and is not published.
- [`src/test/kotlin`](src/test/kotlin) contains the tests, while [`src/test/fixtures`](src/test/fixtures) contains reusable JUnit contracts and fixtures.
- [`src/jmh`](src/jmh) contains benchmarks and benchmark resources.

## Resolver Families

Resolver01–23 are maintained development implementations, not superseded historical snapshots. They serve two purposes:

1. They impose architectural integrity on production Resolution by expressing the same semantic roles and boundaries across increasingly sophisticated execution structures while keeping essential differences explicit.
2. They provide a feature-development ladder: establish a feature in Resolver01–03, extend it through Resolver06–08, carry it into Resolver21–23, and then implement it in production Resolution.

See [Resolver families and alignment](impldocs/architecture/resolver-families.md) for the family comparison and maintenance rules.

## Build and Verification

Open the repository root in IntelliJ and import the repository Gradle build. From the repository root, run the complete Runtime2 check with:

```sh
./gradlew :core:engine:runtime2:check
```

Runtime2 tasks do not support Gradle configuration caching. Kotlin compilation, static analysis, and coverage use repository conventions. Tests and benchmarks can see the unpublished `support` source set; the Runtime2 JAR and runtime publication contain only `main` output.

The vendored GraphQL specification and its rendering build are in [`impldocs/graphql-spec`](impldocs/graphql-spec).

## Documentation

Architecture:

- [Principles](impldocs/architecture/principles.md) records the durable modeling and resolver-design principles.
- [Execution model](impldocs/architecture/execution-model.md) describes the source-world execution model represented by Runtime2.
- [Model](impldocs/architecture/model.md) defines semantic carriers, construction rules, equality, and factory-established invariants.
- [Resolution](impldocs/architecture/resolution.md) describes the production resolution algorithm and its protocols.
- [Resolver families](impldocs/architecture/resolver-families.md) explains the maintained Resolver01–23 families and their relationship to production Resolution.
- [Access checks](impldocs/architecture/access-checks.md) describes access-check semantics and integration.
- [Examples](impldocs/architecture/examples.md) illustrates demand closure, output projection, expansion, and ordering constraints.

Integration:

- [Engine API](impldocs/integration/engine-api.md) describes the execution adapter, current supported surface, and known exclusions.
- [Feature tests](impldocs/integration/feature-tests.md) describes how Runtime2 uses production-derived feature tests to establish compatible behavior and record intentional differences from the old engine.

Testing:

- [Testing guide](impldocs/testing/guide.md) gives the practical testing, replay, debugging, and investigation workflow.
- [Testing strategy](impldocs/testing/strategy.md) explains how contract tests, correctness oracles, generated profiles, exact witnesses, and checker profiles provide independent evidence.
- [Resolution testing](impldocs/testing/resolution.md) documents tests specific to production Resolution.
- [Property tests](impldocs/testing/property-tests.md) describes generated worlds, campaigns, replay, and shrinking.
- [Resolver DSL](impldocs/testing/resolver-dsl.md) defines the deterministic schema-embedded resolver-world DSL.
- [Performance](impldocs/testing/performance.md) documents benchmark and profiling practice.

Correctness and evidence:

- [Claims](impldocs/correctness/claims.md) indexes scoped correctness propositions.
- [Inclusion validation](impldocs/correctness/inclusion-validation.md) describes validation of conditional selection inclusion.
- [`correctness/arguments`](impldocs/correctness/arguments) contains the supporting correctness arguments.
- [Research provenance](impldocs/evidence/research-provenance.md) preserves durable findings and their source provenance.
- [From-object-field census](impldocs/evidence/from-object-field-census.md) records the production-shape census used to choose representative provider-path fixtures.
- [`evidence/profiles`](impldocs/evidence/profiles) contains retained profiling evidence.

The nearest `AGENTS.md` provides an annotated index to the documents relevant to work in its directory.
