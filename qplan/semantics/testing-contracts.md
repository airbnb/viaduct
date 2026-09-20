# Testing Contracts

## Purpose

A testing contract is a reusable suite for one resolver capability. The contract owns fixtures, operations, and assertions; a concrete resolver test supplies only the implementation:

```kotlin
interface ResolverContract {
    fun resolve(
        world: Assumptions,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult
}
```

JUnit 5 discovers `@Test` methods inherited from Kotlin interfaces. A concrete test opts into every supported feature contract and policy mixin.

Organize contracts by user-visible semantic capability, not by the resolver that first exposed a bug. Keep exact result shapes, resolver inputs, application counts, defaults, null and error positions, and other regression-sensitive assertions in the shared contract.

## Feature Contracts

Shared contracts live in `src/testFixtures/kotlin/semantics/contract`:

- `CoroutineResolverContract` is shared by Resolver21-23 and Resolver26. It checks promise installation before producers and ancestor publication, error-valued field and reference failures, dependent error consumption, shared Query-production failure and cancellation with multiple waiting owners, independently rooted reference-target Query production, request failure from primary and Query orchestration, JVM `Error` propagation, runtime read-cycle reporting, and quiescent completion. Resolver-specific suites extend the test-free `CoroutineResolverTestSubject`, which supplies request startup, the demand policy, and blocking resolution helpers; throwing fixtures use direct assertions because the correctness judgment re-invokes the resolver relation.
- `FragmentFreeFieldCheckerPublicationContract` is independently composed into the Resolver21-23 suites alongside `CoroutineResolverContract`, so each suite explicitly names both groups of inherited tests. The contracts share only `CoroutineResolverTestSubject` infrastructure. The checker contract checks value/checker installation before producers, selected active/passive application, grounded occurrence arguments, independent success/denial/absence/failure publication, and cancellation before checker entry or during execution. Resolver21's rejection of checker required selections remains a version-specific test rather than part of this durable fragment-free contract; Resolver26 should adopt the contract when it gains checker support.
- `FrozenObjectResolutionContract` checks that active and passive object occurrences, including nested-list elements, reject new fields after resolution. Every maintained resolver opts into this lifecycle contract.
- `EmptyObjectFragmentResolverContract` covers empty object fragments, arguments, `__typename`, list occurrences, interfaces, and concrete implementation defaults.
- `NodeResolverContract` covers source-level node resolution across every maintained resolver by retaining Node-valued field coordinates, normalizing their outputs to `Query.node` references, dispatching each reference by the concrete type encoded in its global ID, resolving lists element by element, and retaining the originating node ID through a root-reference tail.
- `ObjectFragmentResolverContract` covers nonempty object fragments without variables, including response aliases on argumentless and argument-bearing fields, argument-distinct aliases, non-overlapping concrete-type alternatives, transitive and descendant demand, recursive output, defaults, failures, and occurrence identity.
- `ObjectFragmentFromArgumentResolverContract` covers variables bound from resolver arguments, including nested input-object paths, null intermediate traversal, and a transitive chain.
- `QueryFragmentResolverContract` covers Query-rooted resolver inputs, including response aliases, `FromArgument` bindings consumed by either or both resolver fragments, separation from the primary result, transitive Query-fragment resolution, and occurrence isolation when the same variable-bearing resolver path appears in the request and one or more Query fragments. Every fragment-capable resolver proves one shared Query OER per orchestration, exact-key coalescing, owner-local projections, undemanded empty contexts, and finite expansion with one application per exact key. Boundary cases require an object returned inside a Query scope to own a distinct associated Query OER and require abstract list elements to remain separate containing occurrences whose concrete descendant resolvers contribute independent Query demand. `QueryFragmentFromObjectPathResolverContract` separately covers a Query-fragment use of a `FromObjectField` binding whose provider remains in the object fragment. `FromQueryFieldResolverContract` covers a Query-fragment provider consumed by the object fragment, Query fragment, or both.
- `ObjectFragmentFromObjectPathResolverContract` covers variables bound from exact object-fragment provider paths, including nested paths and scalar-list, null, and error values.
- `VariablesProviderResolverContract` covers Resolver26 variables returned by its one-shot tenant provider, including argument-derived and multiple values, occurrence-local bindings, null, and provider failure.
- `ParentFieldResolverContract` covers structural parent identity, ancestor and descendant demand lifting, list-nested children, abstract parent targets, recursive same-type occurrences, source attempts to replace structural backedges, and cross-occurrence re-entry. Resolver22/23 and Resolver26 implement it. Resolver01–03, Resolver06–08, and Resolver21 require schemas with no `@parent` fields as an input precondition. Their fixtures stay within that domain; rejection of an out-of-domain schema is not part of their test contract. [`examples.md`](../examples.md#why-the-depth-first-resolvers-do-not-support-parent) explains the boundary.
- `SometimesPassiveResolverContract` covers argumentless active fields exceptionally supplied by ancestor resolver outputs. `SometimesPassiveObjectFragmentResolverContract` checks both ownership branches when the standard resolver has input demand, `SometimesPassiveObjectPathResolverContract` preserves a provider path below an ancestor-supplied active field and verifies that binding validation ignores the unbound object-path variables of a skipped standard resolver, and `SometimesPassiveSelectiveResolverContract` witnesses Resolver03, Resolver08, Resolver23, and Resolver26's single selective ancestor application and conservative pre-execution demand.
- `RootFieldReferenceResolverContract` covers demanded object-field and list-element references, scalar and enum targets, conforming abstract targets, grounded target arguments, registered-resolver override, direct reference tails, descendant resolver work below referenced objects, and source ownership. `ObjectFragmentRootFieldReferenceResolverContract` checks that an overridden resolver's input demand is not activated; Resolver02-03, Resolver07-08, Resolver22-23, and Resolver26 implement it. Complete-output implementations also prove that undemanded reference-bearing positions are skipped; Resolver01-08 prove inline depth-first target ordering; fragment-capable implementations prove target Query fragments and `FromArgument`; selective implementations prove full successor demand. Resolver26's additional `RootFieldReferenceResolutionTest` retains its condition, `FromQueryField`, `FromProvider`, parent, and occurrence-observation cases.
- `DepthFirstQueryFringeOrderingContract`, enabled for Resolver02-03 and Resolver07-08, checks that a reference target's independent Query execution completes without consuming the enclosing passive traversal's queued fringe. It also requires the enclosing list references and active descendants to finish before an outer sibling materializes them. `RootFieldReferenceResolverContract` requires every sibling and tail hop to retain a distinct invocation root, and `QueryFragmentRootFieldReferenceResolverContract` proves that ordinary declared fragments reached inside one such execution use singular sharing internally.
- The advanced demand contracts cover recursive-key isolation, deferred demand through passive objects and node references, nested `FromArgument` and `FromObjectField` uses, recursive lists, and acyclic mixed-variable dependency chains.
- `LateObjectPathDemandResolverContract` covers late symbolic demand across already-published active and passive objects.
- `VariableSelectionIdentityResolverContract` checks that equal symbolic arguments coalesce while different variable instances remain distinct even when their bindings agree.
- `GeneratedResolverContract.kt` applies those scopes to generated correctness and permutation properties, includes a sometimes-passive profile with separate generation and activation evidence, and adds a full-feature interaction contract.
- `ListPassiveDeepeningGeneratedResolverContract` biases toward list-valued passive fields and verifies exact witnessed applications when resolver input demand deepens those lists.

Current support is:

| Contract | Resolver01/06/21 | Resolver02/07/22 | Resolver03/08/23 | Resolver26 |
| --- | --- | --- | --- | --- |
| Empty object fragments | yes | yes | yes | yes |
| Source-level node resolution | no | no | no | yes |
| Nonempty object fragments | no | yes | yes | yes |
| Nonempty fragments with `FromArgument` | no | yes | yes | yes |
| Schema with `@parent` fields | outside input domain | Resolver22 only | Resolver23 only | yes |
| Query fragments | no | yes | yes | Resolver26 |
| Query fragments consuming `FromObjectField` | no | no | no | Resolver26 |
| Query fragments producing `FromQueryField` | no | no | no | Resolver26 |
| Nonempty fragments with `FromObjectField` | no | no | no | yes |
| Nonempty fragments with `FromProvider` | no | no | no | yes |
| Root-field references | yes | yes | yes | yes |
| Reference target Query fragments and `FromArgument` | no | yes | yes | yes |
| Reference target `FromQueryField` and `FromProvider` | no | no | no | yes |
| Advanced `FromArgument` demand | no | yes | yes | yes |
| Advanced `FromObjectField` demand | no | no | no | yes |
| Late symbolic object-path demand | no | no | no | yes |
| List-passive deepening generated coverage | no | no | yes | yes |

Runtime `FromObjectField`, `FromQueryField`, and `FromProvider` binding is supported by Resolver26. Every resolver that claims a base feature contract inherits its advanced deterministic regressions. Resolver26 additionally implements provider-function, late symbolic-demand, and symbolic-key-identity contracts.

## Policy Mixins

Policies describe implementation choices that cut across feature scopes:

- `CompleteResolverOutputPolicyContract` and `SelectiveResolverOutputPolicyContract` check unselected passive fields.
- `CompleteObjectFragmentOutputPolicyContract` and `SelectiveObjectFragmentOutputPolicyContract` check recursive passive subtrees reached while satisfying object-fragment demand.
- `CorrectResolutionPostTestPolicy` records results produced through `resolveAndValidate` and validates them in `@AfterEach`.

Resolver01/02/06/07/21/22 use complete-output policies; Resolver03/08/23/26 use selective-output policies. Every contract implementation uses post-test `correctResolution` validation.

Sometimes-passive contracts are enabled for Resolver01-03, Resolver06-08, Resolver21-23, and Resolver26. Resolver02-03, Resolver07-08, Resolver22-23, and Resolver26 additionally run the nonempty-standard-object-fragment cases, and Resolver03, Resolver08, Resolver23, and Resolver26 run the selective one-shot witness.

Deferred validation keeps replayed resolver functions from changing fixture application counters before explicit assertions run. Resolver26 generated observations also retain the exact applied `ResolverOccurrenceId` set. From-field binding validation uses that set to require all and only the `FromObjectField` and `FromQueryField` bindings of applied occurrences, reject any bindings on passive occurrences, compare every binding with its completed provider value, and include Query-fragment roots. There is no unobserved compatibility mode. Every policy mixin must contain an executable guard.

Extended mutation, witness, list-deepening, selective-demand, and stress tests stay separate from ordinary feature acceptance. Mutation, witness, selective-demand, list-deepening, and deep-stress bodies use shared contracts when their assertions are implementation-independent. Resolver23 and Resolver26 enable the fixed `@parent` spine in their deep-stress profiles. Resolver26 additionally opts into the shared mutation, construction-witness, and selective-demand-witness contracts, requires generated and activated sometimes-passive fields and root-field references in deep stress, and runs the randomized parent- and root-reference-focused campaigns described below. The root-reference profile uses a fixed schema family but randomized surrounding schemas, registries, reference insertion sites, and queries; it hard-requires runtime evidence for namespace depths two through four, arities zero/one/four, scalar/enum/concrete-object/interface/union targets, mixed lists, a three-hop tail, active fallback, source override, referenced-result extension resolvers, and target Query fragments using both `FromArgument` and `FromQueryField`. Multithreaded execution remains implementation-specific.

## Resolver Fixture And Oracle Boundary

Direct tests of a resolver algorithm use `FieldResolver.of` and a non-selective resolver function. The model owns projection of that function's stable output to the supplied demand, so contract and property tests can concentrate on whether the algorithm computes and orchestrates the right demand without also trusting fixture-specific selection logic. When a feature test exposes a resolver-algorithm defect, reduce it to a deterministic contract or regression using `FieldResolver.of` before changing the algorithm.

`FieldResolver.ofSelective` exists for integration and feature tests that must pass demand through to a selection-aware executor. Those tests establish that the adapter and end-to-end path expose the expected selection API, but the selective function itself is part of the fixture behavior they trust. In particular, checking one application's output against its supplied demand cannot establish that the function is coherent across different demands. Do not use `ofSelective` as the ordinary basis for resolver-algorithm correctness or property testing; doing so would require stronger independent demand and cross-demand oracles.

`correctResolution` deliberately reapplies the deterministic resolver relation instead of consuming output captured from the runtime application. A completed OER combines fields supplied by an ancestor resolver with fields produced later by standard resolvers and no longer records that source provenance. Reapplication reconstructs the ancestor's output from its completed inputs so the judgment can distinguish passive ancestor-owned fields from standard-resolver-owned fields. Treating recorded runtime output as the expected relation would make that comparison substantially tautological, couple extensional correctness to trace capture, and prevent the same judgment from validating independently constructed or mutated results. The resolver function is therefore part of the reasoning world being used as an oracle; its runtime application is behavior of the algorithm under test.

For a selective relation, reapplication uses the finite `completedOutputDemand` reconstructed from the completed output occurrence. This demand is a canonical validation probe, not a proxy for or witness of the demand originally supplied at runtime. It is sufficient only because selective resolver functions are assumed to keep the same non-object skeleton across demands and to agree at coordinates selected by both demands. Accordingly, `correctResolution` neither proves that the algorithm supplied the right demand nor that a selective function obeyed it. Supplied-demand correctness remains a separate application-witness property.

This boundary keeps existing `FieldResolver.of` algorithm tests assurance-neutral when selective support is added for feature tests. The `ofSelective` integration path does introduce more result/oracle coupling: a wrong demand that causes an extra valid result cell and matching resolver application can sometimes enlarge both the observed result and an expected-application reconstruction. That is an existing limitation of result-derived application oracles, usually concerning absolute minimality unless the extra cell changes ownership or fallback execution. It is accepted for the feature-test role described above; if selective fixtures are later used to make direct resolver-algorithm claims, first close the independent supplied-demand and demanded-occurrence gaps in Resolver26's [exact-application oracle](./src/main/kotlin/semantics/resolver26/testing-resolver26.md#preserve-occurrence-identity-in-the-exact-application-oracle).

## Generated Profiles

| Profile ID | Scope | Resolvers | Normal `S:R:Q` |
| --- | --- | --- | --- |
| `empty-object-fragment` | Empty fragments | Resolver01-03, Resolver06-08, Resolver21-23, Resolver26 | `10:3:5` |
| `node` | Node values normalized to `Query.node` references with concrete-type dispatch | Resolver01-03, Resolver06-08, Resolver21-23, Resolver26 | `10:3:5` |
| `root-field-reference` | Base-tier demanded references with fixed empty target fragments | Resolver01-03, Resolver06-08, Resolver21-23, Resolver26 | `10:3:5` |
| `selective-node` | Selection-aware `Query.node` dispatch with model-owned output projection and supplied-demand evidence | Resolver26 | `10:3:5` |
| `sometimes-passive` | Source-owned argumentless active fields | Resolver01-03, Resolver06-08, Resolver21-23, Resolver26 | `10:3:5` |
| `object-fragment` | Nonempty fragments | Resolver02-03, Resolver07-08, Resolver22-23, Resolver26 | `10:3:5` |
| `object-fragment-from-argument` | `FromArgument` variables, including nested and nullable input paths | Resolver02-03, Resolver07-08, Resolver22-23, Resolver26 | `10:3:5` |
| `from-provider` | One-shot `FromProvider` callback variables with argument-sensitive values | Resolver26 | `10:3:5` |
| `query-fragment` | Query-rooted resolver inputs shared within each orchestration | Resolver02-03, Resolver07-08, Resolver22-23, Resolver26 | `10:3:5` |
| `object-fragment-from-object-field` | `FromObjectField` variables | Resolver26 | `10:3:5` |
| `mixed-variables` | Both variable sources | Resolver26 | fixed aggregate corpus |
| `feature-interaction` | Full ordinary interaction | Resolver02-03, Resolver07-08, Resolver22-23, Resolver26 | `20:3:5` |
| `resolver03-construction-witness` | Construction witness | Resolver03, Resolver26 | `12:2:4` |
| `resolver26-broad-*` | Heterogeneous symbolic-resolution profiles | Resolver26 | opt-in profile-specific products |

Ordinary profiles check whole-result value correctness and completed-result equivalence for permutation-equivalent queries. Field- and type-checker-result slots are not part of resolver correctness or generated cross-version acceptance yet: Resolver21-23 now resolve argument-only field-checker slots under `FragmentFreeFieldCheckerPublicationContract`, while Resolver01-08 and Resolver26 remain value-only and generated worlds contain no checkers. The checker-publication contract and tests dedicated to the result carrier may compare completed checker results, but ordinary resolver contracts and `correctResolution` must not infer an access-check guarantee from resolver output. The extensional resolver comparison requires the same complete value-cell tree and symbolic key structure while comparing occurrence-local variable identities by their root-relative addresses. Profile guards distinguish generation from activation; for example, the node profile requires an actual generated `Query.node` dispatch, the argument-variable profile requires an application of a variable-bearing resolver, the provider-variable profile requires an application of an argument-sensitive provider owner, and the sometimes-passive profile requires registered result occurrences whose standard resolvers were not applied. The `mixed-variables` and `sometimes-passive` profiles apply the caller-provided seed as randomized correctness pressure and use fixed generated seed `1` as their aggregate activation corpus, so a valid random batch cannot fail merely because it misses the promised interaction.

The `root-field-reference` profile disables fragments and variables so it is valid at the base capability tier, then requires both generated and observed references. Its fixed consumer supplies active field values and therefore intentionally omits Resolver26's exact ordinary-application-count assertion: those registered standard resolvers are dynamically passive. Whole-result correctness, permutation equivalence, activation evidence, and Resolver26 from-field binding validation remain enabled.

The Resolver26-only `selective-node` profile retains the complete deterministic generated node program as its value oracle. Its node callback receives node-owned demand through `ofSelectionAwareNonselective`, model-owned projection produces the demanded partial output, and an independent application witness requires that an activated `Query.node` dispatch supplied nonempty demand. The deterministic model fixture test separately verifies that the node-reference adapter passes the exact demand into the callback.

The `query-fragment` profile jointly enables variable-bearing object fragments and Query-rooted resolver fragments, and requires generation and activation evidence for both Query fragments and `FromArgument` variables. Resolver26 additionally enables `FromObjectField` and `FromQueryField` generation in this profile and requires evidence for both kinds of from-field variable; earlier Query-fragment-capable resolvers retain their `FromArgument`-only capability boundary. Its exact application oracle reconstructs applications across both the primary result and every observed associated Query OER, traversing a shared result only once by identity even when multiple owners project it.

Profile IDs are part of the replay interface and must remain stable.

## Replaying Failures

For a failure reporting concrete `S`, `R`, and `Q`, replay that coordinate:

```shell
./gradlew :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolvers.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=2:2:1
```

Coordinate replay regenerates through schema iteration `S` to preserve the random stream, but executes only the selected case and suppresses whole-profile sample and activation guards.

For an aggregate `S=all R=all Q=all` failure, replay the full profile:

```shell
./gradlew :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolvers.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=all
```

`Case=all` retains aggregate guards. `-PresolverPropertySize=S:R:Q` may override profile dimensions only with `Case=all`; a small product can legitimately miss the promised feature and fail activation.

Every failure reports its profile, seed, coordinates, schema, registry, and query. `ResolverTestReplayTest` verifies that coordinate replay reproduces the generated inputs and metadata.

For cross-profile debugging, run the concrete class with only the seed:

```shell
./gradlew :semantics:test \
  --tests 'semantics.resolvers.resolver02.ResolverGeneratedTest' \
  -PresolverPropertySeed=424242
```

Equivalent seed inputs are `RESOLVER_PROPERTY_SEED` and `-Dresolver.property.seed`. Resolver03, Resolver08, Resolver23, and Resolver26 stress use resolver-specific `<resolver>StressSeed` Gradle properties and `<RESOLVER>_STRESS_SEED` environment variables.

## Resolver26 Broad Campaign

Resolver26's broad tests use five directed distributions: balanced worlds, symbolic list descendants, nullable and error providers, equal grounded arguments from distinct symbolic keys, and multiple from-field owners. Every distribution admits query fragments and `FromProvider` variables at bounded density and requires generated and activated query-fragment evidence. The balanced profile additionally requires runtime application of a generated provider-variable owner. Their structural coverage is classified only from completed OER paths and symbolic keys, resolver-application witnesses, and generated registry metadata. Separate request-local binding validation compares the exact required and completed from-field binding sets for every observed application across the primary and Query-fragment roots. No test observes scheduler events, coroutine ordering, or internal demand phases.

The separate `resolver26ParentFocused` task runs a fixed `40:5:5` generated product and divides the resulting 1,000 cases into four consecutive 250-case slices. Parent-enabled cases must include resolver outputs that supply `@parent` fields as well as resolver inputs that traverse them. The profile also enables sometimes-passive generation and uses exact occurrence IDs to count source-supplied active fields whose skipped standard resolver has parent input demand; their maximum parent-demand depths are reported, and at least one such occurrence is a hard aggregate requirement. Runtime materialization and occurrence evidence produces per-slice and combined `HIT`/`MISS` reports plus, for each of nine parent-specific coverage criteria, the number of slices that completely hit it and the number of generated cases that contributed any evidence, both combined and by slice. Individual-slice misses remain diagnostic, but a combined miss across all four slices fails the task; semantic correctness, application accounting, binding validation, and forbidden direct variables beneath parent selections remain independent hard assertions. Deterministic contract tests separately require a resolver reached beneath one parent to demand its own parent while consuming a Query-fragment argument variable from each of `FromArgument`, `FromObjectField`, and `FromQueryField`.

Every generated case checks exact attempted/resolved/completed accounting, root-and-path-qualified application identities, `correctResolution`, and independently reconstructed from-field bindings. Sometimes-passive occurrences form the independently counted difference between registered result occurrences and standard resolver applications. Ordinary broad profiles require general sometimes-passive generation and activation but do not require its incidental intersection with parent demand; that stronger obligation belongs to the random-parent-focused profile, whose generator provides a dedicated witness field. A profile's aggregate run must also observe its required Resolver26 structural signatures.

The checked-in campaign uses fresh JVM rounds and persisted seeds distributed across schema breadth, registry diversity, query interactions, and large/deep worlds. Large/deep worlds bound generated list fanout so the budget explores depth instead of combinatorial list multiplication. Run persisted rounds with:

```shell
./run-property-test-campaign.sh \
  classpath:/semantics/property-tests/campaigns/resolver26-broad-campaign-v1.json \
  21
```

The campaign script builds the standalone property-test launcher once and invokes the serialized campaign directly; it does not run one Gradle or JUnit invocation per round.

Replay one profile or exact coordinate from that round with:

```shell
./gradlew :semantics:resolver26BroadStressCampaign \
  -Presolver26BroadStressCampaignRound=21 \
  -Presolver26BroadStressCampaignProfile=multiple-owners \
  -PresolverPropertyCase=18:4:1
```

Coordinate replay suppresses aggregate structural-coverage requirements while preserving the recorded profile, seed, and generator dimensions. A failing generated case should be reduced to a small deterministic regression test after determining whether the defect belongs to the generator, an independent oracle, or Resolver26.

## Adding Tests

Add a scenario to the narrowest existing feature contract when every implementation claiming that feature must pass it.

Create a feature contract when the scenario establishes a distinct capability with a different support matrix. Create a policy mixin when it establishes an implementation choice shared across feature scopes.

Keep implementation-specific witness, mutation, depth, and stress tests separate when their assertions intentionally exceed the shared capability.

## Observation Configuration

Configure `SharedOperationContext.resolverObserver` (or the contract's `resolverObserver` argument) when executing a resolver. `TestWorld` and `FieldResolver` do not own observation hooks. `ResolverApplicationArguments` in semantics test fixtures records the shared pre-invocation event for DSL argument assertions. Generated cases use `registry.resolverObserver(...)` to capture either full fingerprint witnesses or count-only measurements; supplied-demand capture remains opt-in. Configure mutation recording on this observer as well as the mutated world. Each recorder retains exact invoked occurrence IDs and declared Query roots for validation. Replay of deterministic resolver relations emits no algorithm events. See [the observation inventory](./README.md#resolution-observations) for timing and family coverage.
