# Testing Guide

## Purpose

Runtime2 testing combines contract tests, cross-family comparison, generated tests, correctness-oracle tests, exact-witness tests, mutation tests, and directed stress tests. No one kind is sufficient by itself. Use the smallest kind that can establish the claim under investigation, then broaden validation in proportion to the change.

- **Contract tests** establish deterministic feature behavior, result shape, lifecycle, and policy claims shared by every implementation that adopts a contract; find the reusable interfaces under `src/test/fixtures/viaduct/engine/runtime2/contract` and their concrete adopters under `src/test/kotlin/viaduct/engine/runtime2/resolvers` and `src/test/kotlin/viaduct/engine/runtime2/resolution`.
- **Cross-family comparison** establishes that Resolver01–03, Resolver06–08, Resolver21–23, and production Resolution preserve the same claim at successive architectural boundaries; compare which concrete suites adopt the same inherited contracts using the support matrix in [Contract Tests](strategy.md#contract-tests).
- **Generated tests** establish semantic correctness and exactness over many replayable combinations of schemas, registries, queries, and feature interactions; find shared `Generated*Contract.kt` fixtures under `src/test/fixtures/viaduct/engine/runtime2/contract`, concrete `*GeneratedTest.kt` suites under `src/test/kotlin/viaduct/engine/runtime2`, and campaign mechanics in [Property Testing](property-tests.md).
- **Correctness-oracle tests** establish that completed results independently agree with modeled resolver and checker relations rather than merely with the algorithm's own trace; find direct tests under `src/test/kotlin/viaduct/engine/runtime2/correctresolution`, oracle support under `src/support/kotlin/viaduct/engine/runtime2/correctresolution`, and the boundary definition in [Correctness Oracles](strategy.md#correctness-oracles).
- **Exact-witness tests** establish that the precise semantic occurrences, resolver and checker applications, inputs, bindings, and supplied demands actually occurred, including duplicate-sensitive identities that extensional result correctness cannot prove; find `*WitnessContract.kt` fixtures under `src/test/fixtures/viaduct/engine/runtime2/contract`, concrete `*WitnessTest.kt` suites under `src/test/kotlin/viaduct/engine/runtime2`, and observer semantics in [Observations And Exact Witnesses](strategy.md#observations-and-exact-witnesses).
- **Mutation tests** establish oracle sensitivity by deliberately corrupting resolver programs, completed results, or observations and requiring an independent judgment to reject the corruption; find `ResolverMutationContract.kt` under `src/test/fixtures/viaduct/engine/runtime2/contract` and its concrete `ResolverMutationTest.kt` adopters in the Resolver03 and production Resolution test packages.
- **Directed stress tests** establish behavior under deliberately amplified feature distributions, depth, concurrency, or scheduling pressure beyond the ordinary check; find the opt-in Gradle tasks, profile scopes, and replay commands in [Testing Resolution](resolution.md).

Production Resolution is the final feature target. Resolver01–23 remain active testing infrastructure: they isolate architectural questions and provide the development ladder from compact semantics through explicit work scheduling and structured suspension before a feature reaches production.

Read [architectural principles](../architecture/principles.md), [resolver families](../architecture/resolver-families.md), and the [testing strategy](strategy.md) before changing resolver behavior. Use [Testing Resolution](resolution.md) for production concurrency and stress commands, [property testing](property-tests.md) for generated-campaign mechanics, [performance testing](performance.md) for measurement, and the [resolver DSL](resolver-dsl.md) for small deterministic worlds.

## Validation From The Repository Root

The ordinary full check is:

```shell
./gradlew :core:engine:runtime2:check
```

This covers ordinary model, resolver, correctness, execution, documentation, and generated tests. It does not run opt-in deep stress, broad campaigns, or multithreaded stress tasks.

Start with the narrowest surgical test and broaden only after it passes:

```shell
./gradlew :core:engine:runtime2:test \
  --tests 'viaduct.engine.runtime2.resolution.SymbolicKeyIdentityTest'

./gradlew :core:engine:runtime2:test \
  --tests 'viaduct.engine.runtime2.resolution.*'

./gradlew :core:engine:runtime2:test
```

Run the full check after a cross-cutting semantic, fixture, execution, build, or documentation change. Use the dedicated tasks in [Testing Resolution](resolution.md) only when the change or requested validation warrants their additional cost.

## Use The Resolver-Family Ladder

Choose the earliest family that can express the behavior:

- Resolver01–03 isolate demand closure, exact producer applications, passive deepening, argument grounding, and completed-result correctness.
- Resolver06–08 expose explicit depth-first work ordering and publication without coroutine scheduling.
- Resolver21–23 expose promise installation, suspension, structured request ownership, and checker execution.
- Production Resolution adds symbolic resolver-instance identity, runtime from-field variables, full parent handling, and the complete alpha feature set.

Within each tier, use Resolver01/06/21 for empty object fragments, Resolver02/07/22 for nonempty fragments and `FromArgument`, and Resolver03/08/23 for selective output. A new production feature should normally acquire the relevant earlier-family contracts before it is implemented in Resolution.

## Replay Before Debugging

Generated failures report a stable profile ID, seed, one-based `S:R:Q` coordinate, schema, registry, and query. Replay the exact coordinate before rerunning a class or campaign:

```shell
./gradlew :core:engine:runtime2:resolverPropertyReplay \
  -PresolverPropertyClass=viaduct.engine.runtime2.resolution.ResolverGeneratedTest \
  -PresolverPropertyProfile=feature-interaction \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=2:2:1
```

Coordinate replay regenerates preceding schema state so the random stream remains identical, executes only the selected case, and suppresses aggregate activation guards. Preserve `-PresolverPropertySize=S:R:Q` when the failure came from a stress task because registry and query counts affect generation before the selected coordinate. Use `-PresolverPropertyCase=all` only for aggregate guard failures.

## Classify The Failure

Identify the failing boundary before changing production code:

- **Resolver defect:** wrong value, missing or duplicate writer, invalid binding, wrong occurrence or application identity, stranded required promise, or incorrect lifecycle ownership.
- **Generator defect:** invalid world, unreachable promised feature, bad coercion, or a distribution that cannot construct its advertised interaction.
- **Oracle defect:** circular expectations, lost occurrence identity, result-derived expected demand, incorrect replay, or instrumentation races.
- **Campaign defect:** mismatched distribution, bad coordinate accounting, or an aggregate guard unrelated to the selected profile.
- **Resource-envelope limit:** finite but explosive worlds, heap exhaustion, witness limits, or pathological post-resolution analysis.

For a concurrency-only failure, replay the same coordinate at one thread and several threads. Audit fixture counters, mutable collections, observers, and cleanup before attributing the difference to Resolution.

## Preserve A Useful Counterexample

For a genuine defect:

1. Confirm that the target interaction actually executed.
2. Preserve the profile, seed, coordinate, product size, thread count, schema, registry, and query.
3. Replay only the failing coordinate.
4. Reduce it to a deterministic schema, registry, query, and assertion when possible.
5. Preserve a red regression test before changing production logic.
6. Fix the narrow semantic boundary.
7. Replay the original generated coordinate.
8. Run neighboring family contracts and an appropriately directed production profile.
9. Strengthen generation or activation evidence if the bug class was difficult to reach.

The resolver DSL is preferred for compact counterexamples whose behavior can be represented as a deterministic schema-embedded world. Keep scheduling, observer, bootstrap, and GraphQL-adapter defects in focused Kotlin fixtures at their owning layer.

## Failure And Liveness Policy

Tenant resolvers, checkers, and variables providers may suspend or run slowly. An operation may continue waiting for tenant work after another error makes that work unnecessary. A bounded test around intentionally nonterminating tenant code is a fixture cleanup mechanism, not an engine completion guarantee.

Runtime2 must isolate ordinary tenant failures, publish terminal outcomes for required promises whose producers have exited or been bypassed, enforce access decisions, and honor explicit cancellation. It need not minimize failure latency, maximize partial data after multiple failures, or cancel every producer whose result has become unnecessary. Never repair a local tenant failure by aborting the entire request.

An unfinished demanded cell may mean tenant work is still running, a dependency cycle is being resolved, or a producer has not yet published. That is distinct from a stranded cell after its owning producer and cleanup boundary have finished; the latter is an engine defect. An unresolved demanded cell is never successful completion.

Silence from Gradle is not evidence of deadlock. Inspect CPU, thread stacks, generated-world construction, request timeouts, and post-resolution oracle cost. Distinguish duplicate execution from one-shot exponential growth across distinct occurrences before changing synchronization.

## Fixture And Observation Discipline

`TestWorld` makes intentionally incomplete test registries deterministic by installing null producers for missing nullable Query fields and error producers for missing non-null Query fields before overlaying test declarations. This is a fixture-composition rule, not permission for production bootstrap to omit required entries.

Record runtime events cheaply and thread-safely, snapshot them after request quiescence, and perform expensive correctness analysis serially. Observers must not impose scheduler order. Keep extensional result correctness, exact resolver and checker applications, occurrence identity, variable bindings, lifecycle ownership, supplied demand, mutation evidence, and activation coverage as independent judgments.

An oracle reconstructed from completed result cells can miss an omitted occurrence or accept an extra cell paired with an extra invocation. State such limits explicitly and retain independent witnesses for the properties that matter. In particular, a timeout is not a substitute for an exact producer-application assertion, and wrapper object identity is not a substitute for semantic OER, Query-root, path, and occurrence identity.

## Adding Tests

Add a scenario to the narrowest existing feature contract when every implementation claiming that feature must satisfy it. Create a new feature contract when the scenario defines a distinct capability with a different family support matrix. Create a policy mixin only for an implementation choice that cuts across capability scopes.

Keep production-specific mutation, witness, concurrency, and stress tests separate when their claims intentionally exceed shared family behavior. Preserve exact result shapes, resolver inputs, application counts, null and error positions, and other regression-sensitive assertions in the shared contract rather than duplicating them in each family.
