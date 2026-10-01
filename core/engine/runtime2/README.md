# Runtime2

Runtime2 contains the Kotlin field-resolution model and execution implementation developed in qplan. It is used to state resolver algorithms precisely, compare execution structures, test their behavior over generated worlds, and support formal arguments about selected parts of query execution.

Every maintained resolver uses the aligned engine carrier model, including runtime2's validating `EngineObjectData.Sync` implementation. Resolution is the primary algorithm and eventual implementation blueprint.

Maintaining Resolver01–23 helps preserve Resolution's architectural integrity. Expressing related algorithms through different execution structures forces us to examine how Resolution decomposes and encapsulates its concerns. Shared contracts can deliberately enforce those architectural relationships even when no caller currently consumes them polymorphically; [`resolver-versions.md`](impldocs/resolver-versions.md) explains this role alongside the semantic comparison grid.

Qplan models resolver object fragments and independently resolved Query-rooted fragments. It also models source-sensitive ownership for argumentless fields: a field with a standard resolver is dynamically passive when an ancestor resolver output supplies it and otherwise remains active. Support for these capabilities varies by resolver version; [`resolver-versions.md`](impldocs/resolver-versions.md) and [`semantics/testing-contracts.md`](impldocs/semantics/testing-contracts.md) contain the maintained capability matrix.

The longer-term [runtime2 integration plan](https://slate.airbnb.tools/hSFpbNvtAN) centers Resolution as the production algorithm and retains earlier families in unpublished development support outside `main`. [`resolver-versions.md`](impldocs/resolver-versions.md#production-direction-and-package-ownership) explains how that direction guides today's package ownership. The plan owns the integration milestones and supported subset; implementing runtime2 remains separate from an ordinary qplan refactor.

## Build and IDE Setup

Open the repository root in IntelliJ and import its Gradle build. The former model, semantics, and execution modules are consolidated in `core/engine/runtime2`. Its production `main` source set contains the model, schema adaptation, bootstrap, resolution framework, algorithm, and execution integration. Unpublished `support` contains earlier resolvers, correctness machinery, and neutral fixtures. Concrete tests and JUnit contracts are in `test/kotlin` and `test/fixtures`; benchmarks are in `jmh`.

Use the repository's Gradle wrapper. From the repository root, run `./gradlew -p core :engine:runtime2:check`. Commands elsewhere in this documentation run from `core/engine/runtime2/` and use `../../../gradlew -p ../.. :engine:runtime2:<task>`. Generators live in `src/support/kotlin/viaduct/engine/runtime2/arbitrary`, with their tests in `src/test/kotlin/viaduct/engine/runtime2/arbitrary`. There is no separate qplan project or directory.

Configuration caching remains disabled for runtime2 tasks. Kotlin, static analysis, and coverage use core conventions. Only main output is included in runtime2's JAR and the runtime publication; support and JUnit fixtures are not exported. This layout does not switch StandardViaduct to the new execution strategy. Design documentation and profiling evidence live in `impldocs/`, the vendored GraphQL specification in `spec/`, and formal models in `tla/`. Development scripts and the TLA tool configuration live in this directory. Specification rendering, formal verification, stress campaigns, and benchmark execution remain opt-in.

## Documentation Map

- [`design-principles.md`](impldocs/design-principles.md) states durable modeling and resolver-design principles.
- [`research-evidence.md`](impldocs/research-evidence.md) preserves findings, correctness obligations, hard cases, acceptance cases, prior art, and source provenance behind those principles.
- [`resolver-versions.md`](impldocs/resolver-versions.md) defines cross-family alignment goals and code naming preferences, explains why every maintained resolver exists, and shows how earlier versions help simplify or debug Resolution work.
- [`model/guidelines.md`](impldocs/model/guidelines.md) defines model-world boundaries, including the role of `Assumptions` and the mathematical-signature rules for model dependencies.
- [`semantics/README.md`](impldocs/semantics/README.md) defines semantic contexts, state and task roles, dependency ownership, and the shared resolver boundaries built around `SharedOperationContext`.
- [`viaduct-execution.md`](impldocs/viaduct-execution.md) describes the idealized source-world execution model that qplan represents.
- [`examples.md`](impldocs/examples.md) gives concrete examples of demand closure, output projection, exponential expansion across fresh Query OERs, and the cross-occurrence ordering that prevents the depth-first resolvers from supporting `@parent`.
- [`resolver-test-dsl.md`](impldocs/resolver-test-dsl.md) defines the schema-embedded deterministic resolver-world DSL.
- [`execution/README.md`](impldocs/execution/README.md) describes GraphQL execution, executor-backed feature tests, current limitations, and the next integration slices.
- [`from-object-field-census.md`](impldocs/from-object-field-census.md) preserves a dated production-shape census used to choose representative provider-path fixtures.
- [`maintainer-guide.md`](impldocs/maintainer-guide.md) contains the practical testing, replay, debugging, and investigation workflow.
- [`claims.md`](impldocs/claims.md) indexes scoped propositions; `impldocs/arguments/` contains their supporting reasoning.
- [`tla/README.md`](tla/README.md) defines the machine-checked TLA+ baseline and its refinement boundary.

## Areas

- [`model`](impldocs/model/guidelines.md) defines semantic carriers, construction rules, equality, and factory-established invariants.
- [`semantics`](impldocs/semantics/README.md) defines transformations, correctness judgments, resolver implementations, and test contracts.
- [`arbitrary`](impldocs/arbitrary/README.md) generates canonical schemas, resolver registries, and operations for property testing.
- [`execution`](impldocs/execution/README.md) executes queries through Resolution and provides the Engine API executor feature-test adapter.

The nearest `AGENTS.md` is an annotated index to the documents relevant to work in that directory.
