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
- `FragmentFreeFieldCheckerPublicationContract` is independently composed into the Resolver21-23 and Resolver26 suites alongside `CoroutineResolverContract`, so each suite explicitly names both groups of inherited tests. The contracts share only `CoroutineResolverTestSubject` infrastructure. The checker contract checks value/checker installation before producers, selected active/passive application, grounded occurrence arguments, independent success/denial/absence/failure publication, and cancellation before checker entry or during execution. Resolver21's rejection of checker required selections remains a version-specific test rather than part of this durable fragment-free contract.
- `FragmentFreeFieldCheckerEnforcementContract` is independently and explicitly composed into Resolver22/23 and Resolver26, not inherited through another contract. It checks allowed and denied active/passive resolver dependencies, checker-denial precedence over raw-value errors, enforcement through aliases, `FromArgument` instantiation, and declared Query-fragment inputs, continued invocation when denied object- or Query-rooted inputs remain unread, shared checker results across consumers, checker-defined directive-sensitive applicability without a qplan policy spelling, and exceptional checker completion. Resolver21 has a separate isolation test proving that denial publication does not alter the raw value slot; its empty resolver fragments contain no selected inputs on which enforcement could be observed.
- `FragmentFreeTypeCheckerEnforcementContract` is independently and explicitly composed into Resolver22/23. It checks field-only, type-only, both-success, one-error, and production-combined two-error singular inputs; denial precedence over selected child failures; exceptional and cancellation propagation; repeated consumers with checker-defined directive applicability; raw checker-input bypass; nested-list occurrence paths; root-field-reference elements; parent-backedge reuse; and exact OER-owned type writer/resolver-reader cycle slots. The fragment-free checker profile separately requires exactly one application and stored result per concrete OER occurrence, distinct ordinary list-element applications, raw named inputs, and required-selection rejection. Focused model materialization tests establish the same resolver-applicability, combination-order, recursive-list, raw-error, and independent field-cell/OER-slot primitives below the end-to-end contracts.
- `GroundedFieldCheckerObjectFragmentContract` is composed by Resolver22, Resolver23, and Resolver26. It checks named and empty object members, aliases, `FromArgument` grounding, raw active and nested passive reads, the absence of checker publications induced solely by unchecked checker demand, and runtime rejection of a value/checker wait cycle.
- `GroundedFieldCheckerQueryFragmentContract` is composed by Resolver22, Resolver23, and Resolver26. It checks one associated Query OER for multiple checker occurrences in the same orchestration, unioned construction across named members and owners, independent owner-local raw projections, empty Query members, `FromArgument` grounding, conditional object and Query input shape, the absence of checker publications induced solely by unchecked Query demand, restoration of checking when an unchecked-demanded field's resolver crosses a nested input boundary, exact field-checker reader and value-slot identities across object and shared Query roots, exceptional Query-input materialization, and request cancellation of both work in the associated Query OER and the checker slot.
- `GroundedFieldCheckerCapabilityContract` is composed by Resolver22, Resolver23, and Resolver26. Its load-bearing chain proves that checker A reads active B raw without running B's checker, B's resolver resumes checked demand for C, C success and denial follow the settled resolver-input rules, an ignored denial does not suppress B, service-defined directive applicability affects only B's consumption edge, overlapping named checker demand and ordinary checked demand executes each value and applicable checker once, and raw checker input follows `@parent` backedges.
- `SelectiveFieldCheckerExactnessContract` is Resolver23 and Resolver26's exact access-check witness. It proves that selective producers receive only client, resolver-input, and checker-origin values; raw checker inputs do not acquire their own checks; checked dependencies resume at value-resolver boundaries; overlapping unchecked and checked demand executes one checker; over-returned fields execute none; and exact observations preserve checker kind, logical Query-root identity, occurrence path, arguments, and checked coordinate across primary and associated Query roots. Its duplicate-preserving application judgment is separate from `correctResolution`.
- `FrozenObjectResolutionContract` checks that active and passive object occurrences, including nested-list elements, reject new fields after resolution. Every maintained resolver opts into this lifecycle contract.
- `EmptyObjectFragmentResolverContract` covers empty object fragments, arguments, `__typename`, list occurrences, interfaces, and concrete implementation defaults.
- `NodeResolverContract` covers source-level node resolution across every maintained resolver by retaining Node-valued field coordinates, normalizing their outputs to `Query.node` references, dispatching each reference by the concrete type encoded in its global ID, resolving lists element by element, and retaining the originating node ID through a root-reference tail.
- `ObjectFragmentResolverContract` covers nonempty object fragments without variables, including response aliases on argumentless and argument-bearing fields, argument-distinct aliases, non-overlapping concrete-type alternatives, transitive and descendant demand, recursive output, defaults, failures, and occurrence identity.
- `ObjectFragmentFromArgumentResolverContract` covers variables bound from resolver arguments, including nested input-object paths, null intermediate traversal, and a transitive chain.
- `ResolverInputInclusionContract` is composed by Resolver02, Resolver03, Resolver07, Resolver08, Resolver22, and Resolver23. It checks that `FromArgument` inclusion conditions independently shape both object- and Query-rooted resolver inputs.
- `QueryFragmentResolverContract` covers Query-rooted resolver inputs, including response aliases, `FromArgument` bindings consumed by either or both resolver fragments, separation from the primary result, transitive Query-fragment resolution, and occurrence isolation when the same variable-bearing resolver path appears in the request and one or more Query fragments. Every fragment-capable resolver proves one associated Query OER per orchestration, exact-key coalescing, owner-local projections, undemanded empty contexts, and finite expansion with one application per exact key. Boundary cases require an object returned inside a Query scope to own a distinct associated Query OER and require abstract list elements to remain separate containing occurrences whose concrete descendant resolvers contribute independent Query demand. `QueryFragmentFromObjectPathResolverContract` separately covers a Query-fragment use of a `FromObjectField` binding whose provider remains in the object fragment. `FromQueryFieldResolverContract` covers a Query-fragment provider consumed by the object fragment, Query fragment, or both.
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

Direct tests of a resolver algorithm use `FieldValueResolver.of` and a non-selective resolver function. The model owns projection of that function's stable output to the supplied demand, so contract and property tests can concentrate on whether the algorithm computes and orchestrates the right demand without also trusting fixture-specific selection logic. When a feature test exposes a resolver-algorithm defect, reduce it to a deterministic contract or regression using `FieldValueResolver.of` before changing the algorithm.

`FieldValueResolver.ofSelective` exists for integration and feature tests that must pass demand through to a selection-aware executor. Those tests establish that the adapter and end-to-end path expose the expected selection API, but the selective function itself is part of the fixture behavior they trust. In particular, checking one application's output against its supplied demand cannot establish that the function is coherent across different demands. Do not use `ofSelective` as the ordinary basis for resolver-algorithm correctness or property testing; doing so would require stronger independent demand and cross-demand oracles.

`correctResolution` deliberately reapplies the deterministic resolver relation instead of consuming output captured from the runtime application. A completed OER combines fields supplied by an ancestor resolver with fields produced later by standard resolvers and no longer records that source provenance. Reapplication reconstructs the ancestor's output from its completed inputs so the judgment can distinguish passive ancestor-owned fields from standard-resolver-owned fields. Treating recorded runtime output as the expected relation would make that comparison substantially tautological, couple extensional correctness to trace capture, and prevent the same judgment from validating independently constructed or mutated results. The resolver function is therefore part of the reasoning world being used as an oracle; its runtime application is behavior of the algorithm under test.

For a selective relation, reapplication uses the finite `completedOutputDemand` reconstructed from the completed output occurrence. This demand is a canonical validation probe, not a proxy for or witness of the demand originally supplied at runtime. It is sufficient only because selective resolver functions are assumed to keep the same non-object skeleton across demands and to agree at coordinates selected by both demands. Accordingly, `correctResolution` neither proves that the algorithm supplied the right demand nor that a selective function obeyed it. Supplied-demand correctness remains a separate application-witness property.

This boundary keeps existing `FieldValueResolver.of` algorithm tests assurance-neutral when selective support is added for feature tests. The `ofSelective` integration path does introduce more result/oracle coupling: a wrong demand that causes an extra valid result cell and matching resolver application can sometimes enlarge both the observed result and an expected-application reconstruction. That is an existing limitation of result-derived application oracles, usually concerning absolute minimality unless the extra cell changes ownership or fallback execution. It is accepted for the feature-test role described above; if selective fixtures are later used to make direct resolver-algorithm claims, first close the independent supplied-demand and demanded-occurrence gaps in Resolver26's [exact-application oracle](./src/main/kotlin/semantics/resolver26/testing-resolver26.md#preserve-occurrence-identity-in-the-exact-application-oracle).

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
| `resolver23-field-checker-success` | Grounded paired checker inputs, `FromArgument`, successful checks, correctness replay, and exact applications | Resolver23 | `10:3:5` |
| `resolver23-field-checker-denial` | The same grounded checker input space with access denial and exact error propagation | Resolver23 | `10:3:5` |
| `resolver23-field-checker-mixed` | Mixed checked/unchecked coordinates and mixed successful/denying checker outcomes | Resolver23 | `10:3:5` |
| `resolver23-field-checker-passive` | Source-supplied checked fields with skipped standard resolvers | Resolver23 | `10:3:5`, fixed seed `1` |
| `resolver23-field-checker-root-reference` | Checkers beneath root-reference publication roots and paths | Resolver23 | `10:3:5`, fixed seed `424242` |
| `resolver26-field-checker-*` | Success, denial, mixed, passive, and root-reference checker profiles; all runtime variable sources and exact symbolic applications | Resolver26 | `10:3:5` |
| `object-fragment-from-object-field` | `FromObjectField` variables | Resolver26 | `10:3:5` |
| `mixed-variables` | Both variable sources | Resolver26 | fixed aggregate corpus |
| `feature-interaction` | Full ordinary interaction | Resolver02-03, Resolver07-08, Resolver22-23, Resolver26 | `20:3:5` |
| `resolver03-construction-witness` | Construction witness | Resolver03, Resolver26 | `12:2:4` |
| `resolver26-broad-*` | Heterogeneous symbolic-resolution profiles | Resolver26 | opt-in profile-specific products |

Ordinary generated profiles check whole-result value correctness and completed-result equivalence for permutation-equivalent queries. Resolver23's field-checker profiles install generated checkers at supported resolver coordinates; each checker retains duplicate `left` and `right` resolver-template pairs, adds an empty pair, and adds independent object-only and Query-only aliased `__typename` pairs. Both query permutations run through checker-aware `correctResolution` and a separate duplicate-preserving observer judgment reconstructed from completed checker slots across primary and associated Query OERs. The success and denial profiles hard-require generated and activated evidence for checker presence, nonempty object and Query inputs, ordinary/nested/nullable `FromArgument`, parent demand, duplicate and empty named pairs, checker-only object and Query demand, multiple owners sharing one Query OER, checker execution inside an associated Query OER, list-element occurrences, repeated coordinates, and argument-distinct occurrences. The mixed profile additionally requires successful and denying applications plus resolver applications at deliberately unchecked coordinates. Each profile runs its sampled seed for correctness and exact application accounting; when that sample misses an aggregate coverage signature, fixed seed `424242` supplies the required activation corpus. Fixed-seed directed profiles require source-supplied passive checked fields and checker applications beneath root-reference publication roots and paths. The deterministic query-fragment contract separately proves that checker-only unchecked demand restores checked materialization when an unchecked-demanded resolver consumes a protected dependency. Inclusion remains deterministic rather than generated: `GroundedFieldCheckerQueryFragmentContract` executes both true and false `FromArgument` inclusion branches because arbitrary fragments and queries still do not generate inclusion conditions. Checked client and resolver-input selections require registered checker slots, claimed checker object fragments participate in value closure, each associated Query OER is validated once as the union of its owners' checked and unchecked demand, and every named raw checker input pair is reconstructed from its owner-local projection before the checker relation is replayed. The replayed success/error variant must match the stored checker slot; a checked occurrence at which no checker can run instead requires null. Observed object and Query values passed to field resolvers must equal correctness replay's checked materializations, including access errors at exact Engine value locations. Resolver01–08 and the ordinary Resolver26 profiles generate checker-free worlds; the dedicated Resolver26 checker profiles below exercise runtime checks.

`REPEATED_CHECKER_COORDINATE` records multiple checker applications at one concrete schema field within an execution, typically across different object paths, list elements, or Query roots. Generated-potential accounting includes concrete coordinates repeated in the input selection tree, including interface and union paths, alongside list, duplicate-selection, and Query-fragment metadata; it is independent of observed applications and can overapproximate activation when selections merge. The per-case consistency check requires every activated signature to have generated potential, while aggregate required coverage asks for at least one generated and activated case. Exact checker-application accounting remains a separate assertion.

Run the opt-in Resolver23 checker stress profile with an explicit seed and outcome:

```shell
../gradlew -p . :semantics:resolver23FieldCheckerStress \
  -Presolver23FieldCheckerStressSeed=424242 \
  -Presolver23FieldCheckerStressProfile=success
```

The task runs `50:5:10`, or 2,500 cases. The supported profiles are `success` and `denial`; failures report the stable generated profile ID and exact `S:R:Q` coordinate for `resolverPropertyReplay`.

The `root-field-reference` profile disables fragments and variables so it is valid at the base capability tier, then requires both generated and observed references. Its fixed consumer supplies active field values and therefore intentionally omits Resolver26's exact ordinary-application-count assertion: those registered standard resolvers are dynamically passive. Whole-result correctness, permutation equivalence, activation evidence, and Resolver26 from-field binding validation remain enabled.

The Resolver26-only `selective-node` profile retains the complete deterministic generated node program as its value oracle. Its node callback receives node-owned demand through `ofSelectionAwareNonselective`, model-owned projection produces the demanded partial output, and an independent application witness requires that an activated `Query.node` dispatch supplied nonempty demand. The deterministic model fixture test separately verifies that the node-reference adapter passes the exact demand into the callback.

The `query-fragment` profile jointly enables variable-bearing object fragments and Query-rooted resolver fragments, and requires generation and activation evidence for both Query fragments and `FromArgument` variables. Resolver26 additionally enables `FromObjectField` and `FromQueryField` generation in this profile and requires evidence for both kinds of from-field variable; earlier Query-fragment-capable resolvers retain their `FromArgument`-only capability boundary. Its exact application oracle reconstructs applications across both the primary result and every observed associated Query OER, traversing a shared result only once by identity even when multiple owners project it.

Profile IDs are part of the replay interface and must remain stable.

## Replaying Failures

For a failure reporting concrete `S`, `R`, and `Q`, replay that coordinate:

```shell
../gradlew -p . :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolvers.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=2:2:1
```

Coordinate replay regenerates through schema iteration `S` to preserve the random stream, but executes only the selected case and suppresses whole-profile sample and activation guards.

For an aggregate `S=all R=all Q=all` failure, replay the full profile:

```shell
../gradlew -p . :semantics:resolverPropertyReplay \
  -PresolverPropertyClass=semantics.resolvers.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=all
```

`Case=all` retains aggregate guards. `-PresolverPropertySize=S:R:Q` overrides profile dimensions for either a complete product or one selected case. Replaying a stress coordinate must retain its original product size because registry/query counts affect generation. A small complete product can legitimately miss the promised feature and fail activation.

Every failure reports its profile, seed, coordinates, schema, registry, and query. `ResolverTestReplayTest` verifies that coordinate replay reproduces the generated inputs and metadata.

For cross-profile debugging, run the concrete class with only the seed:

```shell
../gradlew -p . :semantics:test \
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
../gradlew -p . :semantics:resolver26BroadStressCampaign \
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

Configure `SharedOperationContext.resolverObserver` when observing resolver execution and `SharedOperationContext.checkerObserver` when observing checker execution. `TestWorld`, `FieldValueResolver`, and `FieldCheckerResolver` do not own observation hooks. `ResolverApplicationArguments` in semantics test fixtures records the resolver pre-invocation event for DSL argument assertions. `CheckerApplicationRecorder` records duplicate-preserving checker attempts and provides the exact-application multiset judgment. Generated resolver cases use `registry.resolverObserver(...)` to capture either full fingerprint witnesses or count-only measurements; supplied-demand capture remains opt-in. Configure mutation recording on the resolver observer as well as the mutated world. Replay of deterministic resolver relations emits no algorithm events. See [the observation inventory](./README.md#resolution-observations) for timing and family coverage.

## Resolver26 field-checker validation

`SymbolicFieldCheckerTest`, `FieldCheckerClosureTest`, and `FieldCheckerLifecycleTest` add four-forest closure, raw-to-checked boundaries, distinct symbolic keys with equal arguments, pair-local providers, aliases with physical overlap, null/error provider paths on both roots, independent raw `ctx.query()` scopes, cancellation before entry, provider exceptions, and bounded nontermination. `InclusionConditionTest` also requires terminal absent-checker publication when activation fails. `RequestScopeOwnershipTest` admits checker dispatch only from the two coroutine orchestration implementations.

`FieldCheckerGeneratedTest` explicitly adopts the generated checker contract and the Resolver26 correctness/permutation policies. Runtime success, denial, and mixed profiles require activated `FromArgument`, `FromObjectField`, `FromQueryField`, `FromProvider`, and symbolic-key signatures alongside the grounded profile obligations. Passive and root-reference profiles retain directed activation. Each observed execution is checked by checker-aware `correctResolution` and a separate duplicate-preserving checker-application multiset. Runtime inclusion and alias conditions remain deterministic witnesses.

The runtime checker distributions use `ResolverFragmentDepth=1`: two independently bound named pairs can multiply symbolic dependency trees, so copying depth-two fragments at every registered coordinate produced a case with over 129,000 resolver invocations before the 15-second request bound. This limit bounds random workload construction without changing request timeouts, per-case oracles, or required activation signatures. Fixed parent spines still exercise multilevel parent demand, and deterministic contracts retain nested fragment and named-provider combinations. The grounded Resolver23 distributions retain depth two.

Run the replayable stress task from `qplan/`: `../gradlew -p . :semantics:resolver26FieldCheckerStress -Presolver26FieldCheckerStressSeed=424242`. Its default size is `50:5:10` (2,500 cases), its profile defaults to `success`, and `-Presolver26FieldCheckerStressProfile=denial` selects denial. It also accepts `mixed`, `passive`, and `root-reference`, and a `-Presolver26FieldCheckerStressSize=<schemas>:<registries>:<queries>` override. Set `-Pviaduct.resolution.threadcount=100` for concurrent execution with the same oracle and exactness assertions. Failures use the ordinary `resolverPropertyReplay` task with the reported `resolver26-field-checker-*` profile, seed, size, and coordinates.
