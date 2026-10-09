# Feature Tests

## Purpose

The [`runtime`](../../../runtime) engine is Runtime2's principal behavioral comparison surface. Its feature tests contain years of executable expectations about the Engine API, GraphQL completion, resolver inputs, nodes, selective execution, and failures. Runtime2 copies applicable tests so that aligned behavior remains visible and regressions are caught at the integration boundary.

The `runtime` engine is not the semantic authority for Runtime2. Runtime2's contracts are defined by its [execution model](../architecture/execution-model.md), [canonical model](../architecture/model.md), and [production Resolution](../architecture/resolution.md). A disagreement may expose a Runtime2 defect, an alpha integration gap, or an intentional difference between occurrence-oriented one-shot resolution and the `runtime` engine's materialization and cache behavior. The copied tests distinguish those cases instead of treating every disagreement as a migration TODO.

## Comparison Method

Production-derived tests live under `src/test/kotlin/viaduct/engine/runtime2/execution/viaductfeaturetests`. Each copied file identifies its `runtime` engine source path and synchronization point in a header comment. The copy remains source-faithful apart from package and import plumbing, the change from `runFeatureTest` to `runQPlanFeatureTest`, source metadata, coded `@Disabled` annotations, and explicit `ALTERNATIVE` cases. `runQPlanFeatureTest` builds `StandardViaduct` with `ENGINE2_ENABLED` and exercises module-config bootstrap and production dispatcher wiring, including access checkers and scoped schemas. It does not use the direct executor adapter.

An unchanged passing test establishes aligned behavior through the test's public response and any executor observations it asserts. Disabled tests use one of three classifications:

- `TODO: <capability>` identifies an alpha capability or adapter gap that should eventually allow the `runtime` engine test to run unchanged.
- `N/A: <reason>` identifies a test whose subject is outside this comparison abstraction, such as `runtime` engine cache internals, an instrumentation-only assertion, or a harness that cannot run through `EngineTestModule`.
- `ALT: <difference>` preserves the `runtime` engine expectation when Runtime2 intentionally has a different contract. A nearby test whose name begins with `ALTERNATIVE` exercises the corresponding Runtime2 behavior and preserves the comparable response, error, or resolver-input claim where that claim still applies.

The annotations in the copied source are the authoritative per-test compatibility record. This document describes the stable rules behind those annotations; it does not duplicate changing test counts, line-number inventories, or enablement history.

## Aligned Behavior

The copied tests establish substantial alignment for query and ordered mutation execution, GraphQL response completion, ordinary and selective field and node executors, field and type access checks, required selections, aliases, arguments, fragments, conditional directives, namespaces, built-in node lookup, root-field references, and synchronous success and error outputs. They also cover object- and Query-rooted resolver and checker inputs, supported variable providers, nested `ctx.query()` and `ctx.mutation()` calls, scoped executable schemas backed by full-schema resolver inputs, and preservation of meaningful GraphQL error paths. The shared `CoroutineContextContract` in `runtime` test fixtures additionally verifies caller request context across resolver suspension and dispatcher switches for both engines, without framework dependencies. Production execution preserves the configured `CoroutineInterop` bridge for thread-local coroutine helpers.

Selective node execution preserves nested demand for equal node IDs reached through distinct query paths. The enabled `batched selective node cache distinguishes nested selections across query paths` case in [SelectiveNodeResolversExecutionTest](../../src/test/kotlin/viaduct/engine/runtime2/execution/viaductfeaturetests/SelectiveNodeResolversExecutionTest.kt) requests `bar { nested { x } }` and `foo { bar { nested { y } } }` for the same `Bar` ID and verifies both projected responses and the separate observed selections `{x}` and `{y}`. Its batching-capable executors run through singleton dispatcher adaptation; physical batching and cache reuse remain separate integration gaps.

Alignment is a claim about the behavior observed by a particular test, not about identical implementation machinery. Runtime2 may reach the same response through one closed producer application where the `runtime` engine uses materialization retries, or through distinct result occurrences where the `runtime` engine uses a request cache. Tests that depend on those internal differences are classified explicitly rather than counted as aligned merely because their final data happens to match.

## Intentional Semantic Differences

| Subject | Runtime2 contract | `runtime` engine comparison |
| --- | --- | --- |
| Producer execution | Each resolver occurrence receives its statically closed demand and applies its producer once. | Selective sources may be materialized or refetched repeatedly as demand and runtime types become known. |
| Variable identity | Each resolver variable name has one symbolic, occurrence-local binding shared by that occurrence's object and Query inputs. | Callback-owned object and Query inputs may bind the same name independently. |
| Associated Query input | Owners in one containing orchestration use its singular associated Query OER; an explicit nested query or reference target has a fresh Query root. Runtime2 does not memoize declared Query work across independent roots. | Query work may be reused through materialization and request-cache machinery. |
| Root references | Every reference occurrence, including each direct-result tail hop, receives a fresh invocation identity even when two descriptors are equal. | Equivalent references may be physically deduplicated. |
| Reference inputs | A reference target has an empty object required-selection set and no `FromObjectField` variables. Its dependencies are Query-rooted. | The `runtime` engine's representation does not impose the same semantic boundary. |
| Node values | Every Node value crosses the built-in `Query.node` boundary; a tenant resolver cannot inline-materialize a Node-valued field. | A resolver may directly materialize some Node-valued objects while using references for others. |
| Equal node IDs | Equal IDs in distinct result positions remain distinct occurrences with separately closed demand and results. | Node data-loader and cache policy may merge or accumulate work for a shared identity. |
| Parent dependencies | Variables are prohibited anywhere beneath `@parent`; child-produced information cannot parameterize ancestor work. | Callback-based required-selection machinery can express some of these dependency directions. |
| Passive field ownership | Ownership is source-sensitive for argumentless registered fields: an ancestor that actually supplies the field owns that occurrence; otherwise its standard resolver owns it. Argument-bearing fields are always active. | Materialization may route argument-bearing or consumer-shaped values through a producer's passive output. |
| Selective output | A selective producer must conform to its closed output selection. Surplus fields are a producer-contract violation, and registered descendant fields retain their own ownership. | Materialization can ignore, reconcile, or reuse surplus output from covering results. |
| Directives and checks | Source occurrences retain generic field-directive context. Runtime2 assigns no built-in meaning to a policy-specific directive spelling. | Production policy integrations may interpret particular directives directly. |
| Mutation dependencies | Mutation resolvers use `ctx.query()` for dependent reads; each nested `ctx.mutation()` preserves its own order while independent calls may overlap. | Mutation resolvers may declare Query required selections. |
| Error evidence | Compatibility establishes corresponding error outcomes and meaningful consumer paths. Runtime2's correctness oracle does not yet prove exact `EngineErrorData` carrier identity or metadata equality at every derived boundary. | Tests may inspect `runtime` engine wrapper, materialization-source, or carrier details that are not part of Runtime2's semantic claim. |

The `ALT` plus `ALTERNATIVE` pairing is especially important for these differences. The disabled source form keeps the `runtime` engine expectation reviewable, while the alternative prevents an intentional difference from becoming an untested exemption.

## Alpha Integration Gaps

The following differences are gaps in the present Engine API integration rather than permanent semantic incompatibilities:

- physical batching and any compatible expansion of production data-loader reuse outside semantic occurrence scheduling;
- mutation namespace checkers, subscriptions, custom scalars, `@stream`, and asynchronous EOD variants;
- direct `Engine.resolveRootFieldReference` calls and `EngineExecutionContext.completeSelectionSet`; and
- opaque checker variables providers with their own required selections, checker enforcement on `@parent` backedges and reference target paths, and other resolver and checker combinations listed as unsupported by [Engine API integration](engine-api.md).

[Engine API integration](engine-api.md) defines the current adapter surface and its rejection boundaries. A copied test blocked only by one of these gaps should retain its `runtime` engine form with a specific `TODO` classification so that closing the gap means enabling the original test rather than inventing a Runtime2-specific substitute.

## `runtime` Tests Outside This Comparison

Not every `runtime` engine feature test belongs in the copied suite. Tests centered on production-only helper APIs, query-plan or execution-selection-set representation, tenant bootstrap validation, data-loader cache policy, batching mechanics, shadow execution, or `runtime` engine instrumentation internals do not directly test Runtime2's resolver and GraphQL integration boundary. Likewise, inherited arbitrary suites that construct a production `Viaduct` without exposing an `EngineTestModule` cannot be run through this adapter.

Excluding such a test is not evidence that its user-visible behavior is unimportant. If the behavior belongs to Runtime2, it should be covered at the layer that owns it: canonical model and resolver contracts for semantics, adapter tests for Engine API conversion, GraphQL execution tests for response completion, generated property tests for broad resolver coverage, or dispatcher and service integration tests for physical execution policy.

Operation-validation tests are also normally outside this suite because GraphQL Java rejects invalid source operations before `QPlanExecutionStrategy` starts Resolution. Runtime2-specific decoding and registry validation remain covered by focused adapter tests.

## Maintaining The Port

When a `runtime` engine source file changes, synchronize the whole copied file and update its source metadata. Do not silently cherry-pick only the newly passing cases. Keep fixtures, helpers, assertions, and test names unchanged unless adapter plumbing requires a mechanical edit.

Classify every divergence by its durable cause. Use `TODO` only when implementing a supported capability should make the original test pass; use `N/A` only when the test observes machinery outside the comparison; and use `ALT` only for a deliberate Runtime2 contract. An `ALT` must retain the source expectation and have an `ALTERNATIVE` that directly demonstrates the Runtime2 rule.

New Engine API features should first be established through Runtime2's resolver-family ladder and focused integration tests, then compared with the corresponding `runtime` engine tests. Passing a `runtime` engine test is valuable compatibility evidence, but it does not replace the architectural-integrity contracts maintained by [Resolver01–03](../../impldocs/architecture/resolver-families.md#comparison-grid), Resolver06–08, and Resolver21–23 before the feature reaches production Resolution.
