# Plan Cache

## Role

The plan cache removes repeated planning work from Engine2 requests without changing what Resolution computes. Planning is the deterministic part of a request: decoding the operation into a selection forest, closing each object occurrence's construction demand, deriving each resolver's successor demand, and normalizing passive demand. Resolver work, values, list cardinality, concrete types, and occurrence identity stay per request.

The cache is enabled by the `ENGINE2_PLAN_CACHE` flag, which `EngineFactory` passes to `Engine2` as `usePlanCache`; `Assumptions.usePlanCache` carries the same choice into Resolution. Turning the flag off restores the uncached code paths exactly.

## Primary Key

The validated GraphQL `Document` instance is the primary key. The service document cache (`CachingPreparsedDocumentProvider` or any `PreparsedDocumentProvider` that returns stable entries) supplies the same instance for every execution of an operation text, so identity is a free and exact operation key. Every lower layer is keyed by the identity of a selection forest that the document's plan keeps alive. Selection equality is undefined by design, so no layer compares forests structurally; equal-looking forests from different plans never share entries.

## Layers

| Layer | Owner | Key | Value |
| --- | --- | --- | --- |
| Operation | `OperationSelectionCache` in `Engine2` | document identity, operation definition identity, registry identity, locale, and the coerced variable values when the operation declares variables | decoded root `SelectionForest` |
| Closure | memo on the incoming checked forest, or the unchecked forest when checked demand is empty | registry identity, closure algorithm, partner forest identity, type-check provenance, concrete type, presence signature, type-checker applicability | closed object-side and Query-side forests plus their resolver keys, field checkers, and type-check condition |
| Successor demand | memo on the publication construction demand's forest | registry identity, partner forest identity, type-check provenance, possible root types | the producer's successor demand forest |
| Passive demand | memo on the closed value forest | invocation demand identity | the concatenated passive demand, whose existing per-forest merge memo then also hits |

The presence signature is a bit per registered argument-free field of the concrete type, set when the source supplies that field. It is the only source read that affects closure: presence decides whether a registered field keeps its standard resolver. A source that supplies a registered argument-bearing field bypasses the cache so the uncached closure reports the invalid output as before. Root-field references do not affect closure and are rediscovered on every hit.

## Instantiation

A closure hit returns no stored occurrence. It recreates the resolver, field-checker, and type-checker contexts from the stored keys at the new occurrence, which instantiates their fragments, variable definitions, cycle tasks, and provider reads with the new root and path, and then builds the closed context through the same `closedOERConstructionDemandOf` used by uncached finalization. The stored forests are shared verbatim. Children receive descendants of those shared forests, so their own closures, successor demand, merges, and passive demand hit by identity as well; the cascade reaches every level of a list-heavy response and every later request of the same document.

## Eligibility

The first step caches only what is exact without symbolic operation variables or occurrence renaming:

- The operation layer caches query operations whose document has no `@skip` or `@include`. Operations without variable definitions keep one forest; others keep up to 16 forests keyed by coerced values. Mutations, subscriptions, and conditional operations decode per request.
- A closure template is recorded only when no resolver, field checker, or type checker expanded on either side owns variables, no provider read exists, and no parent key is demanded. Such forests contain no variable instance owned by the computing occurrence, so they are valid at every occurrence that consumes the same incoming forest.
- A concrete type with more than 64 registered argument-free fields closes without the cache, because its presence signature does not fit one word.
- Nested `ctx.query()` and `ctx.mutation()` executions decode fresh forests per call. Their memos die with those forests.

Every per-forest entry list is bounded to 16 entries per kind, and every document keeps at most 32 operation plans, so unbounded key diversity cannot grow a long-lived plan. Entries are weakly reachable from the document cache: evicting a document releases its whole plan.

## Correctness Argument

Each memoized computation is a pure function of the inputs its key records. Closure reads the incoming demand, the registry and algorithm, the concrete type, source presence, and type-checker applicability; it uses occurrence identity only to name the contexts and variable instances it creates. Eligibility excludes the variable instances, and instantiation recreates the contexts, so a hit yields the context that uncached closure would have produced up to sharing of immutable forests. Successor demand and passive concatenation are pure functions of their recorded inputs. One-shot producer correctness is preserved because each resolver still receives the successor demand derived from its own occurrence's closed demand.

`OperationSelectionCacheTest` checks document-identity reuse, per-value caching of operations with variables, and the conditional and mutation exclusions. `PlanCacheTest` checks cached and uncached agreement on results, `correctResolution`, and resolver application counts over lists of abstract types, the sharing of one template per concrete type across list elements, reuse without new entries on a repeated request, and the variable exclusion. `PlanCacheBenchmark` additionally compares every response of its generated workload with an uncached engine during trial setup.

## Measurement

`./gradlew :core:engine:runtime2:planCacheBenchmark --console=plain` measures the first step's workload through `StandardViaduct`. The workload is generated from a fixed seed with `planCacheStepOneConfig()`: field and node resolvers with object and Query fragments, lists, interfaces, and unions, without conditions, parents, references, sometimes-passive fields, checkers, or resolver variables. Generated field names collide across object types, so trial setup aliases one field of each overlapping-field conflict until every operation validates. `planCache` selects `off` or `on`; `documents=shared` gives each execution of an operation the same cached document, while `documents=fresh` gives every execution an unseen document so that only reuse within one request remains. Override the seed with `-PplanCacheBenchmarkSeed=S` and the batch repetition with `-PresolverBenchmarkLoopCount=M`.

## Later Steps

Each later step widens eligibility without changing the layer structure:

1. Symbolic operation variables, so one forest serves all variable values of a document, with a request-start check that falls back to a value-specialized forest when two distinct variables or a variable and a literal ground to equal arguments in one object, and a completion lookup from symbolic to ground keys.
2. Directive-bit specialization for `@skip`/`@include`, keying the operation layer by the Boolean values of directive variables.
3. Resolver variables, by renaming the computing occurrence's variable instances on a hit and keying children by template position instead of forest identity.
4. `@parent`, nested operations keyed by their selection documents, and plan-driven completion.
