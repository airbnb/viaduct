# Qplan Maintainer Guide

## Working Context

Follow the current explicit prompt and [`handoff.md`](./handoff.md) before this guide. The handoff records qplan's current state without assigning an immediate objective. Resolver26 is the primary algorithm and eventual production implementation; the [runtime2 direction](./resolver-versions.md#production-direction-and-package-ownership) keeps earlier families as development support. Treat runtime2 integration as longer-term context unless it is explicitly requested.

## Read Before Changing Code

1. [`README.md`](./README.md) for the project map and integration state.
2. [`handoff.md`](./handoff.md) for current state and scope boundaries.
3. [`design-principles.md`](./design-principles.md) for durable semantic constraints.
4. [`resolver-versions.md`](./resolver-versions.md) for cross-family alignment policy, code naming preferences, and comparison and reduction roles.
5. [`semantics/testing-contracts.md`](./semantics/testing-contracts.md) for test capabilities and replay.
6. The local Resolver26 design document when changing Resolver26.

## Use The Resolver Grid

The maintained older resolvers are a reduction and comparison grid. Start with Resolver03 when a failure concerns demand closure, exact application count, passive deepening, argument grounding, or completed-result correctness without `FromObjectField`. Move to Resolver08 when explicit work ordering or publication may matter, then to Resolver23 when promise installation, suspension, or structured scope ownership may matter.

Use Resolver01/06/21 to remove nonempty object fragments and Resolver02/07/22 to retain object fragments and `FromArgument` without selective-output pressure. Use Resolver26 when behavior depends on runtime from-field variables or symbolic resolver-instance identity.

Resolver10 is a source of warnings, not a debugging baseline: readiness rescanning, persistent late-demand acceptance, and complete-output retention can obscure the producer-completeness question.

## Fast Validation Loop

Run commands from `qplan/`.

```shell
../gradlew -p . check
```

`check` covers ordinary model, arbitrary, semantics, execution, and documentation checks. It excludes deep stress, broad campaigns, and multithreaded stress.

Use the narrowest relevant module or test class before broadening:

```shell
../gradlew -p . :model:test
../gradlew -p . :arbitrary:test
../gradlew -p . :semantics:test
../gradlew -p . :semantics:test --tests 'semantics.resolver26.SymbolicKeyIdentityTest'
../gradlew -p . :semantics:test --tests 'semantics.resolver26.*'
```

Resolver26 concurrency, stress, and CPU-probe commands live in [`testing-resolver26.md`](./semantics/src/main/kotlin/semantics/resolver26/testing-resolver26.md). Benchmark commands and reporting requirements live in [`resolver-benchmarks.md`](./semantics/resolver-benchmarks.md).

## Replay Before Debugging

Generated failures report a profile, seed, one-based `S:R:Q` coordinate, schema, registry, and query. Replay that exact coordinate before rerunning a class or campaign:

```shell
../gradlew -p . :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolver26.ResolverGeneratedTest \
  -PresolverPropertyProfile=feature-interaction \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=2:2:1
```

Coordinate replay preserves the random stream through schema iteration `S`, executes only the selected case, and suppresses aggregate activation guards. Use `Case=all` only for aggregate guard failures.

## Classify The Failure

Before changing resolver code, identify the failing boundary:

- **Resolver:** wrong result, missing or duplicate writer, invalid binding, wrong application identity, or engine-created liveness failure under the [tenant progress assumptions](./design-principles.md#tenant-failure-isolation-and-progress).
- **Generator:** invalid world, unreachable promised feature, bad coercion, or missing generation capability.
- **Oracle:** shared assumptions, lost occurrence identity, result-derived expectations, or instrumentation races.
- **Campaign:** mismatched distribution, bad case accounting, or probabilistic aggregate guards.
- **Resource envelope:** finite but explosive worlds, witness limits, heap exhaustion, or pathological oracle complexity.

For concurrency failures, replay at one worker and several workers. Audit fixture counters, mutable lists, and observers before attributing a multithread-only failure to Resolver26.

## Preserve A Useful Counterexample

The preferred investigation sequence is:

1. Require evidence that the target interaction actually executed.
2. Preserve the profile, seed, coordinate, thread count, schema, registry, and query.
3. Replay only the failing coordinate.
4. Reduce it to a deterministic schema, registry, query, and assertion.
5. Preserve the red regression before changing production logic.
6. Fix the narrow semantic boundary.
7. Replay the original coordinate.
8. Run neighboring contracts and an appropriately directed stress profile.
9. Improve generation or activation checks so the bug class remains discoverable.

## Fixture Composition Contracts

`TestWorld` makes incomplete test registries deterministic by supplying a null-producing resolver for each missing nullable Query field and an error-producing resolver for each missing non-null Query field before overlaying the resolvers declared by the test. A missing non-null declaration therefore remains an explicit error if execution reaches it, while incomplete feature-test modules can omit unrelated nullable roots. This is a test-fixture composition contract, not permission for a production resolver registry to omit required entries.

## Diagnose Liveness And Scale

Silence from Gradle is not evidence of deadlock. Check process CPU, thread stacks, resolver timeouts, generated-world construction, and post-resolution oracle cost before adding synchronization.

Use `jps -lv`, `jstack`, `jcmd <pid> Thread.print`, or a profiler. For an OOMing case, capture the generated world without resolving it, then add bounded launch and depth diagnostics. Distinguish duplicate execution from one-shot exponential growth across distinct occurrences.

Before classifying a timeout as a resolver defect, establish whether tenant resolver, checker, or variables-provider code is still running or deliberately never returns. The operation may wait for that work even if another error has made its output unnecessary and no live consumer still needs it. Prompt cancellation of such work and optimal recovery from multiple errors are not acceptance requirements. Do not fix a local tenant failure by aborting the whole request; preserve its owned error boundary and the specified propagation rules.

An unresolved demanded cell may reflect unfinished tenant work, a missing writer, a dependency cycle, failed task ownership, or invalid quiescence. Waiting on unfinished tenant work is allowed; a stranded required promise after its producer exits or is bypassed is a defect. An unresolved demanded cell is never successful completion.

## Maintain Independent Evidence

Keep extensional correctness, application identities, occurrence identities, from-field bindings, lifecycle invariants, mutation tests, metamorphic variants, and structural activation as separate evidence sources.

An oracle derived from returned cells can miss an omitted occurrence or accept an extra cell paired with an extra invocation. State such limitations explicitly and preserve independent reconstruction work as an open testing task.

During resolution, instrumentation must be thread-safe and cheap. Snapshot after request quiescence, then perform expensive witness and correctness analysis serially. Test instrumentation must not impose scheduler order.

## Documentation Maintenance

`README.md` files own stable module explanations. `AGENTS.md` files are annotated indexes that point to those explanations and say when they matter. `handoff.md` owns current state, explicit scope boundaries, and longer-term context. `design-principles.md` owns durable principles. `resolver-versions.md` owns cross-family alignment policy and code naming preferences, linking to canonical semantic definitions in `semantics/README.md`. Resolver-local design and testing files own implementation-specific protocols. Git history owns completed chronology.

Keep each prose paragraph and list item on one physical line. Document factory-established carrier invariants on the factory with an `### Invariant: kebab-case-label` heading. Invariant labels and claim labels share one namespace checked by `checkDocumentationLabels`.

Record stable propositions in [`claims.md`](./claims.md) with one-sentence statements and put their scoped reasoning in `arguments/<claim-label>.md`. Keep the claim and argument synchronized.

Concrete resolver examples should present a complete top-down GraphQL schema followed by the triggering query. Annotate passive and resolver fields, resolver object fragments, variable sources, and returned values before explaining execution.
