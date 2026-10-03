# Testing Strategy

## Evidence Map

Runtime2 establishes confidence through several independent kinds of tests and judgments. They share fixtures and observations, but each answers a different question; agreement among them must not be manufactured by deriving every expectation from the same completed result or event stream.

| Evidence kind | Primary question | Section |
| --- | --- | --- |
| Contract tests | Does every resolver family claiming a capability satisfy the same deterministic behavior and lifecycle rules? | [Contract Tests](#contract-tests) |
| Cross-family comparison | Do the same inherited contracts remain true across the compact, depth-first, coroutine, and production resolver architectures? | [Contract Tests](#contract-tests) |
| Generated tests | Do many replayable schemas, registries, queries, and feature interactions satisfy the same semantic and exactness judgments? | [Generated Tests](#generated-tests) |
| Correctness-oracle tests | Does a completed result agree extensionally with the modeled resolver and checker relations? | [Correctness Oracles](#correctness-oracles) |
| Exact-witness tests | Did the expected semantic occurrences, inputs, applications, bindings, and demands actually execute? | [Observations And Exact Witnesses](#observations-and-exact-witnesses) |
| Mutation tests | Do independent judgments reject deliberately corrupted programs, results, and observations? | [Correctness Oracles](#correctness-oracles) |
| Directed stress tests | Do targeted feature distributions, depth, concurrency, and scheduling pressure preserve the relevant claims beyond the ordinary check? | [Production Broad Campaign](#production-broad-campaign) and [Testing Resolution](resolution.md) |

This document defines what those evidence layers mean and how they compose. Checker profiles are specialized generated tests and directed stress tests, not an additional evidence kind; [Checker Profiles](#checker-profiles) defines their feature-specific correctness, exactness, and activation obligations. [Testing Guide](guide.md) owns day-to-day validation and investigation workflow, [Testing Resolution](resolution.md) owns production concurrency and stress commands, [Property Testing](property-tests.md) owns generator and campaign mechanics, and [Feature Tests](../integration/feature-tests.md) owns behavioral comparison with the old engine.

## Contract Tests

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

For Resolver01–23, unsupported inputs are outside the resolver's test domain: fixtures and composed contracts omit them. An implementation may reject such an input when that falls out naturally, but rejection is not a required behavior and should not add capability flags or validation machinery. Resolution is the production algorithm and must reject unsupported inputs deterministically.

### Tenant Failure And Liveness

Tests and audits follow the [tenant failure and progress policy](../architecture/principles.md#isolate-tenant-failure-without-stranding-work). If a tenant resolver, checker, or variables-provider callback is slow or hangs, the operation may wait for it, even after another failure removes its last consumer. A timeout in a fixture that deliberately never returns from tenant code does not by itself demonstrate an engine liveness defect. Such fixtures must bound the test and explicitly cancel or release their tenant work during cleanup; the fixture's deadline is not an operation-completion requirement.

Require local error isolation, correct access enforcement, terminal publication for required promises whose producer has exited or been bypassed, and the specified explicit cancellation cleanup. Reject request-wide abort as a fallback for ordinary tenant errors. Do not require minimal failure latency, maximal partial results, or cancellation of every unnecessary producer in multiple-error scenarios.

### Feature Contracts

Shared contracts live in `src/test/fixtures/viaduct/engine/runtime2/contract`:

- `CoroutineResolverContract` is shared by Resolver21-23 and Resolution. It checks promise installation before producers and ancestor publication, error-valued field and reference failures, dependent error consumption, shared Query-production failure and cancellation with multiple waiting owners, independently rooted reference-target Query production, request failure from primary and Query orchestration, JVM `Error` propagation, runtime read-cycle reporting, and quiescent completion. Resolver-specific suites extend the test-free `CoroutineResolverTestSubject`, which supplies request startup, the demand policy, and blocking resolution helpers; throwing fixtures use direct assertions because the correctness judgment re-invokes the resolver relation.
- `FragmentFreeFieldCheckerPublicationContract` is independently composed into the Resolver21-23 and Resolution suites alongside `CoroutineResolverContract`, so each suite explicitly names both groups of inherited tests. The contracts share only `CoroutineResolverTestSubject` infrastructure. The checker contract checks value/checker installation before producers, selected active/passive application, grounded occurrence arguments, independent success/denial/absence/failure publication, and cancellation before checker entry or during execution. Resolver21 fixtures remain fragment-free; nonempty checker required selections are outside that resolver's input domain.
- `FragmentFreeFieldCheckerEnforcementContract` is independently and explicitly composed into Resolver22/23 and Resolution, not inherited through another contract. It checks allowed and denied active/passive resolver dependencies, checker-denial precedence over raw-value errors, enforcement through aliases, `FromArgument` instantiation, and declared Query-fragment inputs, continued invocation when denied object- or Query-rooted inputs remain unread, shared checker results across consumers, checker-defined directive-sensitive applicability without a Runtime2-owned policy spelling, and exceptional checker completion. Resolver21 has a separate isolation test proving that denial publication does not alter the raw value slot; its empty resolver fragments contain no selected inputs on which enforcement could be observed.
- `FragmentFreeTypeCheckerEnforcementContract` is independently and explicitly composed into Resolver22/23 and Resolution. It checks field-only, type-only, both-success, one-error, and production-combined two-error singular inputs; denial precedence over selected child failures; exceptional and cancellation propagation; repeated consumers with checker-defined directive applicability; raw checker-input bypass; nested-list occurrence paths; root-field-reference elements; parent-backedge reuse; and exact OER-owned type writer/resolver-reader cycle slots. The fragment-free checker profile separately requires exactly one application and stored result per concrete OER occurrence, distinct ordinary list-element applications, and raw named inputs. Focused model materialization tests establish the same resolver-applicability, combination-order, recursive-list, raw-error, and independent field-cell/OER-slot primitives below the end-to-end contracts.
- `GroundedTypeCheckerFragmentContract` is independently and explicitly composed into Resolver22/23 and Resolution. It checks empty, object-only, Query-only, and paired named raw inputs; unioned construction with separate named projections; repeated concrete OER occurrences; suppression of field checks reached only through raw type-checker demand; restoration of checked dependencies at an active value-resolver boundary; and Query-input materialization failure on the OER-owned type slot. Type-checker variables are outside Resolver22/23's supported input domain and are omitted from the contract. Resolver21's fragment-free profile installs only fragment-free type checkers; nonempty type-checker RSS is likewise outside that resolver's contract rather than a required rejection behavior. Resolver23's rooted successor boundary separately expands possible concrete type-checker object fragments before selective producer invocation.
- `GroundedTypeCheckerLifecycleContract`, composed by Resolver22/23 and Resolution, checks a type-checker/value-resolver wait cycle through a checked parent edge and request cancellation while a type checker awaits its associated Query input. These assertions inspect terminal promises directly; failed relations are outside completed-result correctness replay.
- `SelectiveTypeCheckerExactnessContract`, composed by Resolver23 and Resolution, checks exact producer demand and duplicate-preserving field/type application multisets separately from `correctResolution`. It covers raw objects with checked active dependencies, checked/raw overlap and denial bypass, over-returned fields, abstract nested lists with empty selected subtrees, distinct equal-valued occurrences, target-qualified Query ownership, parent paths through nested lists, independently rooted references, and preservation of an early field denial before a later type denial during correctness replay. `ResolverInputReplayTest` rejects unrelated or inapplicable errors offered as early-denial witnesses. `TypeCheckerCorrectResolutionTest` mutates completed results and Query witnesses to require rejection of missing type slots, missing raw inputs, wrong result variants, missing/duplicate/wrong-target Query associations, and incorrect Query values.
- `GroundedFieldCheckerObjectFragmentContract` is composed by Resolver22, Resolver23, and Resolution. It checks named and empty object members, aliases, `FromArgument` grounding, raw active and nested passive reads, the absence of checker publications induced solely by unchecked checker demand, and runtime rejection of a value/checker wait cycle.
- `GroundedFieldCheckerQueryFragmentContract` is composed by Resolver22, Resolver23, and Resolution. It checks one associated Query OER for multiple checker occurrences in the same orchestration, unioned construction across named members and owners, independent owner-local raw projections, empty Query members, `FromArgument` grounding, conditional object and Query input shape, the absence of checker publications induced solely by unchecked Query demand, restoration of checking when an unchecked-demanded field's resolver crosses a nested input boundary, exact field-checker reader and value-slot identities across object and shared Query roots, exceptional Query-input materialization, and request cancellation of both work in the associated Query OER and the checker slot.
- `GroundedFieldCheckerCapabilityContract` is composed by Resolver22, Resolver23, and Resolution. Its load-bearing chain proves that checker A reads active B raw without running B's checker, B's resolver resumes checked demand for C, C success and denial follow the settled resolver-input rules, an ignored denial does not suppress B, service-defined directive applicability affects only B's consumption edge, overlapping named checker demand and ordinary checked demand executes each value and applicable checker once, and raw checker input follows `@parent` backedges.
- `SelectiveFieldCheckerExactnessContract` is Resolver23 and Resolution's exact access-check witness. It proves that selective producers receive only client, resolver-input, and checker-origin values; raw checker inputs do not acquire their own checks; checked dependencies resume at value-resolver boundaries; overlapping unchecked and checked demand executes one checker; over-returned fields execute none; and exact observations preserve checker kind, logical Query-root identity, occurrence path, arguments, and checked coordinate across primary and associated Query roots. Its duplicate-preserving application judgment is separate from `correctResolution`.
- `FrozenObjectResolutionContract` checks that active and passive object occurrences, including nested-list elements, reject new fields after resolution. Every maintained resolver opts into this lifecycle contract.
- `EmptyObjectFragmentResolverContract` covers empty object fragments, arguments, `__typename`, list occurrences, interfaces, and concrete implementation defaults.
- `NodeResolverContract` covers source-level node resolution across every maintained resolver by retaining Node-valued field coordinates, normalizing their outputs to `Query.node` references, dispatching each reference by the concrete type encoded in its global ID, resolving lists element by element, and retaining the originating node ID through a root-reference tail.
- `ObjectFragmentResolverContract` covers nonempty object fragments without variables, including response aliases on argumentless and argument-bearing fields, argument-distinct aliases, non-overlapping concrete-type alternatives, transitive and descendant demand, recursive output, defaults, failures, and occurrence identity.
- `ObjectFragmentFromArgumentResolverContract` covers variables bound from resolver arguments, including nested input-object paths, null intermediate traversal, and a transitive chain.
- `ResolverInputInclusionContract` is composed by Resolver02, Resolver03, Resolver07, Resolver08, Resolver22, and Resolver23. It checks that `FromArgument` inclusion conditions independently shape both object- and Query-rooted resolver inputs.
- `QueryFragmentResolverContract` covers Query-rooted resolver inputs, including response aliases, `FromArgument` bindings consumed by either or both resolver fragments, separation from the primary result, transitive Query-fragment resolution, and occurrence isolation when the same variable-bearing resolver path appears in the request and one or more Query fragments. Every fragment-capable resolver proves one associated Query OER per orchestration, exact-key coalescing, owner-local projections, undemanded empty contexts, and finite expansion with one application per exact key. Boundary cases require an object returned inside a Query scope to own a distinct associated Query OER and require abstract list elements to remain separate containing occurrences whose concrete descendant resolvers contribute independent Query demand. `QueryFragmentFromObjectPathResolverContract` separately covers a Query-fragment use of a `FromObjectField` binding whose provider remains in the object fragment. `FromQueryFieldResolverContract` covers a Query-fragment provider consumed by the object fragment, Query fragment, or both.
- `ObjectFragmentFromObjectPathResolverContract` covers variables bound from exact object-fragment provider paths, including nested paths and scalar-list, null, and error values.
- `VariablesProviderResolverContract` covers Resolution variables returned by its one-shot tenant provider, including argument-derived and multiple values, occurrence-local bindings, null, and provider failure.
- `ParentFieldResolverContract` covers structural parent identity, ancestor and descendant demand lifting, list-nested children, abstract parent targets, recursive same-type occurrences, source attempts to replace structural backedges, and cross-occurrence re-entry. Resolver22/23 and Resolution implement it. Resolver01–03, Resolver06–08, and Resolver21 require schemas with no `@parent` fields as an input precondition. Their fixtures stay within that domain; rejection of an out-of-domain schema is not part of their test contract. [Resolution examples](../architecture/examples.md#why-the-depth-first-resolvers-do-not-support-parent) explain the boundary.
- `SometimesPassiveResolverContract` covers argumentless active fields exceptionally supplied by ancestor resolver outputs. `SometimesPassiveObjectFragmentResolverContract` checks both ownership branches when the standard resolver has input demand, `SometimesPassiveObjectPathResolverContract` preserves a provider path below an ancestor-supplied active field and verifies that binding validation ignores the unbound object-path variables of a skipped standard resolver, and `SometimesPassiveSelectiveResolverContract` witnesses Resolver03, Resolver08, Resolver23, and Resolution's single selective ancestor application and conservative pre-execution demand.
- `RootFieldReferenceResolverContract` covers demanded object-field and list-element references, scalar and enum targets, conforming abstract targets, grounded target arguments, registered-resolver override, direct reference tails, descendant resolver work below referenced objects, and source ownership. `ObjectFragmentRootFieldReferenceResolverContract` checks that an overridden resolver's input demand is not activated; Resolver02-03, Resolver07-08, Resolver22-23, and Resolution implement it. Complete-output implementations also prove that undemanded reference-bearing positions are skipped; Resolver01-08 prove inline depth-first target ordering; fragment-capable implementations prove target Query fragments and `FromArgument`; selective implementations prove full successor demand. Resolution's additional `RootFieldReferenceResolutionTest` retains its condition, `FromQueryField`, `FromProvider`, parent, and occurrence-observation cases.
- `DepthFirstQueryFringeOrderingContract`, enabled for Resolver02-03 and Resolver07-08, checks that a reference target's independent Query execution completes without consuming the enclosing passive traversal's queued fringe. It also requires the enclosing list references and active descendants to finish before an outer sibling materializes them. `RootFieldReferenceResolverContract` requires every sibling and tail hop to retain a distinct invocation root, and `QueryFragmentRootFieldReferenceResolverContract` proves that ordinary declared fragments reached inside one such execution use singular sharing internally.
- The advanced demand contracts cover recursive-key isolation, deferred demand through passive objects and node references, nested `FromArgument` and `FromObjectField` uses, recursive lists, and acyclic mixed-variable dependency chains.
- `LateObjectPathDemandResolverContract` covers late symbolic demand across already-published active and passive objects.
- `VariableSelectionIdentityResolverContract` checks that equal symbolic arguments coalesce while different variable instances remain distinct even when their bindings agree.
- `GeneratedResolverContract.kt` applies those scopes to generated correctness and permutation properties, includes a sometimes-passive profile with separate generation and activation evidence, and adds a full-feature interaction contract.
- `ListPassiveDeepeningGeneratedResolverContract` biases toward list-valued passive fields and verifies exact witnessed applications when resolver input demand deepens those lists.

Current support is:

| Contract | Resolver01/06/21 | Resolver02/07/22 | Resolver03/08/23 | Resolution |
| --- | --- | --- | --- | --- |
| Empty object fragments | yes | yes | yes | yes |
| Source-level node resolution | no | no | no | yes |
| Nonempty object fragments | no | yes | yes | yes |
| Nonempty fragments with `FromArgument` | no | yes | yes | yes |
| Schema with `@parent` fields | outside input domain | Resolver22 only | Resolver23 only | yes |
| Query fragments | no | yes | yes | yes |
| Query fragments consuming `FromObjectField` | no | no | no | yes |
| Query fragments producing `FromQueryField` | no | no | no | yes |
| Nonempty fragments with `FromObjectField` | no | no | no | yes |
| Nonempty fragments with `FromProvider` | no | no | no | yes |
| Root-field references | yes | yes | yes | yes |
| Reference target Query fragments and `FromArgument` | no | yes | yes | yes |
| Reference target `FromQueryField` and `FromProvider` | no | no | no | yes |
| Advanced `FromArgument` demand | no | yes | yes | yes |
| Advanced `FromObjectField` demand | no | no | no | yes |
| Late symbolic object-path demand | no | no | no | yes |
| List-passive deepening generated coverage | no | no | yes | yes |

Runtime `FromObjectField`, `FromQueryField`, and `FromProvider` binding is supported by Resolution. Every resolver that claims a base feature contract inherits its advanced deterministic regressions. Resolution additionally implements provider-function, late symbolic-demand, and symbolic-key-identity contracts.

### Policy Mixins

Policies describe implementation choices that cut across feature scopes:

- `CompleteResolverOutputPolicyContract` and `SelectiveResolverOutputPolicyContract` check unselected passive fields.
- `CompleteObjectFragmentOutputPolicyContract` and `SelectiveObjectFragmentOutputPolicyContract` check recursive passive subtrees reached while satisfying object-fragment demand.
- `CorrectResolutionPostTestPolicy` records results produced through `resolveAndValidate` and validates them in `@AfterEach`.

Resolver01/02/06/07/21/22 use complete-output policies; Resolver03/08/23 and production Resolution use selective-output policies. Every contract implementation uses post-test `correctResolution` validation.

Sometimes-passive contracts are enabled for Resolver01-03, Resolver06-08, Resolver21-23, and Resolution. Resolver02-03, Resolver07-08, Resolver22-23, and Resolution additionally run the nonempty-standard-object-fragment cases, and Resolver03, Resolver08, Resolver23, and Resolution run the selective one-shot witness.

Deferred validation keeps replayed resolver functions from changing fixture application counters before explicit assertions run. Resolution generated observations also retain the exact applied `ResolverOccurrenceId` set. From-field binding validation uses that set to require all and only the `FromObjectField` and `FromQueryField` bindings of applied occurrences, reject any bindings on passive occurrences, compare every binding with its completed provider value, and include Query-fragment roots. There is no unobserved compatibility mode. Every policy mixin must contain an executable guard.

Extended mutation, witness, list-deepening, selective-demand, and stress tests stay separate from ordinary feature acceptance. Mutation, witness, selective-demand, list-deepening, and deep-stress bodies use shared contracts when their assertions are implementation-independent. Resolver23 and Resolution enable the fixed `@parent` spine in their deep-stress profiles. Resolution additionally opts into the shared mutation, construction-witness, and selective-demand-witness contracts, requires generated and activated sometimes-passive fields and root-field references in deep stress, and runs the randomized parent- and root-reference-focused campaigns described below. The root-reference profile uses a fixed schema family but randomized surrounding schemas, registries, reference insertion sites, and queries; it hard-requires runtime evidence for namespace depths two through four, arities zero/one/four, scalar/enum/concrete-object/interface/union targets, mixed lists, a three-hop tail, active fallback, source override, referenced-result extension resolvers, and target Query fragments using both `FromArgument` and `FromQueryField`. Multithreaded execution remains implementation-specific.

## Correctness Oracles

Correctness oracles validate completed results independently from the algorithm's runtime control flow. They are deliberately narrower than a proof of scheduling, lifecycle, or supplied demand, so the suite pairs them with exact observations and mutation tests.

### Resolver Fixture And Oracle Boundary

`CheckerProviderCorrectResolutionTest` mutates provider bindings while keeping checker and field outputs unchanged, covering both checker kinds, named object/Query pairs, equal variable names, nullable/list values, and provider argument tuples. `ReferenceQueryTypeCheckerCorrectResolutionTest` mutates independently executed Query roots to remove or forge their type results and checker inputs; associated roots remain valid without a type result. It also checks all-excluded selections and validation-cache reuse. Provider and checker relations are reapplied during correctness judgment, so runtime invocation counters must be asserted before replay.

Direct tests of a resolver algorithm use `FieldValueResolver.of` and a non-selective resolver function. The model owns projection of that function's stable output to the supplied demand, so contract and property tests can concentrate on whether the algorithm computes and orchestrates the right demand without also trusting fixture-specific selection logic. When a feature test exposes a resolver-algorithm defect, reduce it to a deterministic contract or regression using `FieldValueResolver.of` before changing the algorithm.

`FieldValueResolver.ofSelective` exists for integration and feature tests that must pass demand through to a selection-aware executor. Those tests establish that the adapter and end-to-end path expose the expected selection API, but the selective function itself is part of the fixture behavior they trust. In particular, checking one application's output against its supplied demand cannot establish that the function is coherent across different demands. Do not use `ofSelective` as the ordinary basis for resolver-algorithm correctness or property testing; doing so would require stronger independent demand and cross-demand oracles.

`correctResolution` deliberately reapplies the deterministic resolver relation instead of consuming output captured from the runtime application. A completed OER combines fields supplied by an ancestor resolver with fields produced later by standard resolvers and no longer records that source provenance. Reapplication reconstructs the ancestor's output from its completed inputs so the judgment can distinguish passive ancestor-owned fields from standard-resolver-owned fields. Treating recorded runtime output as the expected relation would make that comparison substantially tautological, couple extensional correctness to trace capture, and prevent the same judgment from validating independently constructed or mutated results. The resolver function is therefore part of the reasoning world being used as an oracle; its runtime application is behavior of the algorithm under test.

For a selective relation, reapplication uses the finite `completedOutputDemand` reconstructed from the completed output occurrence. This demand is a canonical validation probe, not a proxy for or witness of the demand originally supplied at runtime. It is sufficient only because selective resolver functions are assumed to keep the same non-object skeleton across demands and to agree at coordinates selected by both demands. Accordingly, `correctResolution` neither proves that the algorithm supplied the right demand nor that a selective function obeyed it. Supplied-demand correctness remains a separate application-witness property.

`correctResolution` establishes extensional completed-result agreement, required checker slots, replayable named checker inputs, and the success/error variants represented by its relations. It does not establish execution order, original supplied demand, all runtime variable bindings, task ownership, cancellation cleanup, or concurrency. Exact application recorders, binding validators, lifecycle contracts, cancellation tests, and multithreaded campaigns own those claims separately.

This boundary keeps existing `FieldValueResolver.of` algorithm tests assurance-neutral when selective support is added for feature tests. The `ofSelective` integration path does introduce more result/oracle coupling: a wrong demand that causes an extra valid result cell and matching resolver application can sometimes enlarge both the observed result and an expected-application reconstruction. That is an existing limitation of result-derived application oracles, usually concerning absolute minimality unless the extra cell changes ownership or fallback execution. It is accepted for the feature-test role described above; direct resolver-algorithm claims from selective fixtures require closing the independent supplied-demand and demanded-occurrence gaps in Resolution's [exact-application oracle](resolution.md#preserve-occurrence-identity-in-the-exact-application-oracle).

## Generated Tests

Generated tests exercise the shared semantic contracts over reproducible products of schemas, registries, and queries. Stable profile IDs and exact coordinates turn a broad randomized failure into a replayable case, while activation guards prevent a green profile from claiming a feature it never executed.

### Profile Catalog

| Profile ID | Scope | Resolvers | Normal `S:R:Q` |
| --- | --- | --- | --- |
| `empty-object-fragment` | Empty fragments | Resolver01-03, Resolver06-08, Resolver21-23, Resolution | `10:3:5` |
| `node` | Node values normalized to `Query.node` references with concrete-type dispatch | Resolver01-03, Resolver06-08, Resolver21-23, Resolution | `10:3:5` |
| `root-field-reference` | Base-tier demanded references with fixed empty target fragments | Resolver01-03, Resolver06-08, Resolver21-23, Resolution | `10:3:5` |
| `selective-node` | Selection-aware `Query.node` dispatch with model-owned output projection and supplied-demand evidence | Resolution | `10:3:5` |
| `sometimes-passive` | Source-owned argumentless active fields | Resolver01-03, Resolver06-08, Resolver21-23, Resolution | `10:3:5` |
| `object-fragment` | Nonempty fragments | Resolver02-03, Resolver07-08, Resolver22-23, Resolution | `10:3:5` |
| `object-fragment-from-argument` | `FromArgument` variables, including nested and nullable input paths | Resolver02-03, Resolver07-08, Resolver22-23, Resolution | `10:3:5` |
| `from-provider` | One-shot `FromProvider` callback variables with argument-sensitive values | Resolution | `10:3:5` |
| `query-fragment` | Query-rooted resolver inputs shared within each orchestration | Resolver02-03, Resolver07-08, Resolver22-23, Resolution | `10:3:5` |
| `resolver23-field-checker-success` | Grounded paired checker inputs, `FromArgument`, successful checks, correctness replay, and exact applications | Resolver23 | `10:3:5` |
| `resolver23-field-checker-denial` | The same grounded checker input space with access denial and exact error propagation | Resolver23 | `10:3:5` |
| `resolver23-field-checker-mixed` | Mixed checked/unchecked coordinates and mixed successful/denying checker outcomes | Resolver23 | `10:3:5` |
| `resolver23-type-checker-success` | Raw object/Query value resolvers, checked dependency restoration, mixed field checks | Resolver23 | `10:3:5` |
| `resolver23-type-checker-denial` | The same type-checker input space with denying type results | Resolver23 | `10:3:5` |
| `resolver23-type-checker-mixed` | Successful and denying concrete types with mixed field checks | Resolver23 | `10:3:5` |
| `resolver23-field-checker-passive` | Source-supplied checked fields with skipped standard resolvers | Resolver23 | `10:3:5`, fixed seed `1` |
| `resolver23-field-checker-root-reference` | Checkers beneath root-reference publication roots and paths | Resolver23 | `10:3:5`, fixed seed `424242` |
| `resolution-type-checker-*` | Mixed field/type outcomes, grounded activation guards plus a coordinate-seeded runtime-variable witness family | Resolution | `10:3:5` |
| `resolution-field-checker-*` | Success, denial, mixed, passive, and root-reference checker profiles; all runtime variable sources and exact symbolic applications | Resolution | `10:3:5` |
| `object-fragment-from-object-field` | `FromObjectField` variables | Resolution | `10:3:5` |
| `mixed-variables` | Both variable sources | Resolution | fixed aggregate corpus |
| `feature-interaction` | Full ordinary interaction | Resolver02-03, Resolver07-08, Resolver22-23, Resolution | `20:3:5` |
| `resolver03-construction-witness` | Construction witness | Resolver03, Resolution | `12:2:4` |
| `resolution-broad-*` | Heterogeneous symbolic-resolution profiles | Resolution | opt-in profile-specific products |

Ordinary generated profiles check whole-result value correctness and completed-result equivalence for permutation-equivalent queries. Resolver23's field-checker profiles install generated checkers at supported resolver coordinates; each checker retains duplicate `left` and `right` resolver-template pairs, adds an empty pair, and adds independent object-only and Query-only aliased `__typename` pairs. Both query permutations run through checker-aware `correctResolution` and a separate duplicate-preserving observer judgment reconstructed from completed checker slots across primary and associated Query OERs. The success and denial profiles hard-require generated and activated evidence for checker presence, nonempty object and Query inputs, ordinary/nested/nullable `FromArgument`, parent demand, duplicate and empty named pairs, checker-only object and Query demand, multiple owners sharing one Query OER, checker execution inside an associated Query OER, list-element occurrences, repeated coordinates, and argument-distinct occurrences. The mixed profile additionally requires successful and denying applications plus resolver applications at deliberately unchecked coordinates. Each profile runs its sampled seed for correctness and exact application accounting; when that sample misses an aggregate coverage signature, fixed seed `424242` supplies the required activation corpus. Fixed-seed directed profiles require source-supplied passive checked fields and checker applications beneath root-reference publication roots and paths. The deterministic query-fragment contract separately proves that checker-only unchecked demand restores checked materialization when an unchecked-demanded resolver consumes a protected dependency. Inclusion remains deterministic rather than generated: `GroundedFieldCheckerQueryFragmentContract` executes both true and false `FromArgument` inclusion branches because arbitrary fragments and queries still do not generate inclusion conditions. Checked client and resolver-input selections require registered checker slots, claimed checker object fragments participate in value closure, each associated Query OER is validated once as the union of its owners' checked and unchecked demand, and every named raw checker input pair is reconstructed from its owner-local projection before the checker relation is replayed. The replayed success/error variant must match the stored checker slot; a checked occurrence at which no checker can run instead requires null. Observed object and Query values passed to field resolvers must equal correctness replay's checked materializations, including access errors at exact Engine value locations. Resolver01–08 and the ordinary Resolution profiles generate checker-free worlds; the dedicated Resolution checker profiles below exercise runtime checks.

`REPEATED_CHECKER_COORDINATE` records multiple checker applications at one concrete schema field within an execution, typically across different object paths, list elements, or Query roots. Generated-potential accounting includes concrete coordinates repeated in the input selection tree, including interface and union paths, alongside list, duplicate-selection, and Query-fragment metadata; it is independent of observed applications and can overapproximate activation when selections merge. The per-case consistency check requires every activated signature to have generated potential, while aggregate required coverage asks for at least one generated and activated case. Exact checker-application accounting remains a separate assertion.

Run the opt-in Resolver23 access-check campaign from the repository root with an explicit seed:

```shell
./gradlew :core:engine:runtime2:resolver23AccessCheckerStress \
  -Presolver23AccessCheckerStressSeed=424242
```

The default `all` campaign runs six profiles: `success`, `denial`, and `mixed` field checks, plus `type-success`, `type-denial`, and `type-mixed` type checks with mixed field checks. Each profile runs `50:5:10` (2,500 cases), totaling 15,000 generated cases and two fresh executions per case for permutation comparison. Select one with `-Presolver23AccessCheckerStressProfile=type-denial`; override the product with `-Presolver23AccessCheckerStressSize=50:5:10`. The older `resolver23FieldCheckerStress` task and its existing seed/profile properties remain supported with their original default `success`; it also accepts the six profiles and `all`. Both entry points accept the corresponding system properties (`resolver23.access.checker.stress.*` or `resolver23.field.checker.stress.*`) and environment variables (`RESOLVER23_ACCESS_CHECKER_STRESS_*` or `RESOLVER23_FIELD_CHECKER_STRESS_*`).

Failures report the stable generated profile ID, actual seed, and exact `S:R:Q` coordinate. Preserve the original product size when replaying a stress coordinate:

```shell
./gradlew :core:engine:runtime2:resolverPropertyReplay \
  -PresolverPropertyClass=viaduct.engine.runtime2.resolvers.resolver23.ResolverGeneratedTest \
  -PresolverPropertyProfile=resolver23-type-checker-denial \
  -PresolverPropertySeed=424242 \
  -PresolverPropertySize=50:5:10 \
  -PresolverPropertyCase=1:1:1
```

The `root-field-reference` profile disables fragments and variables so it is valid at the base capability tier, then requires both generated and observed references. Its fixed consumer supplies active field values and therefore intentionally omits Resolution's exact ordinary-application-count assertion: those registered standard resolvers are dynamically passive. Whole-result correctness, permutation equivalence, activation evidence, and Resolution from-field binding validation remain enabled.

The Resolution-only `selective-node` profile retains the complete deterministic generated node program as its value oracle. Its node callback receives node-owned demand through `ofSelectionAwareNonselective`, model-owned projection produces the demanded partial output, and an independent application witness requires that an activated `Query.node` dispatch supplied nonempty demand. The deterministic model fixture test separately verifies that the node-reference adapter passes the exact demand into the callback.

The `query-fragment` profile jointly enables variable-bearing object fragments and Query-rooted resolver fragments, and requires generation and activation evidence for both Query fragments and `FromArgument` variables. Resolution additionally enables `FromObjectField` and `FromQueryField` generation in this profile and requires evidence for both kinds of from-field variable; earlier Query-fragment-capable resolvers retain their `FromArgument`-only capability boundary. Its exact application oracle reconstructs applications across both the primary result and every observed associated Query OER, traversing a shared result only once by identity even when multiple owners project it.

Profile IDs are part of the replay interface and must remain stable.

### Replaying Failures

For a failure reporting concrete `S`, `R`, and `Q`, replay that coordinate:

```shell
./gradlew :core:engine:runtime2:resolverPropertyReplay \
  -PresolverPropertyClass=viaduct.engine.runtime2.resolvers.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=2:2:1
```

Coordinate replay regenerates through schema iteration `S` to preserve the random stream, but executes only the selected case and suppresses whole-profile sample and activation guards.

For an aggregate `S=all R=all Q=all` failure, replay the full profile:

```shell
./gradlew :core:engine:runtime2:resolverPropertyReplay \
  -PresolverPropertyClass=viaduct.engine.runtime2.resolvers.resolver02.ResolverGeneratedTest \
  -PresolverPropertyProfile=node \
  -PresolverPropertySeed=424242 \
  -PresolverPropertyCase=all
```

`Case=all` retains aggregate guards. `-PresolverPropertySize=S:R:Q` overrides profile dimensions for either a complete product or one selected case. Replaying a stress coordinate must retain its original product size because registry/query counts affect generation. A small complete product can legitimately miss the promised feature and fail activation.

Every failure reports its profile, seed, coordinates, schema, registry, and query. `ResolverTestReplayTest` verifies that coordinate replay reproduces the generated inputs and metadata.

For cross-profile debugging, run the concrete class with only the seed:

```shell
./gradlew :core:engine:runtime2:test \
  --tests 'viaduct.engine.runtime2.resolvers.resolver02.ResolverGeneratedTest' \
  -PresolverPropertySeed=424242
```

Equivalent seed inputs are `RESOLVER_PROPERTY_SEED` and `-Dresolver.property.seed`. Resolver03, Resolver08, Resolver23, and Resolution stress use resolver-specific `<resolver>StressSeed` Gradle properties and `<RESOLVER>_STRESS_SEED` environment variables.

### Production Broad Campaign

Resolution's broad tests use five directed distributions: balanced worlds, symbolic list descendants, nullable and error providers, equal grounded arguments from distinct symbolic keys, and multiple from-field owners. Every distribution admits query fragments and `FromProvider` variables at bounded density and requires generated and activated query-fragment evidence. The balanced profile additionally requires runtime application of a generated provider-variable owner. Their structural coverage is classified only from completed OER paths and symbolic keys, resolver-application witnesses, and generated registry metadata. Separate request-local binding validation compares the exact required and completed from-field binding sets for every observed application across the primary and Query-fragment roots. No test observes scheduler events, coroutine ordering, or internal demand phases.

The separate `resolutionParentFocused` task runs a fixed `40:5:5` generated product and divides the resulting 1,000 cases into four consecutive 250-case slices. Random parent chains begin beneath an ordinary root object, preserving three-to-four parent-link depth while excluding backedges to Query; feature counts include only actual parent fields. Parent-enabled cases must include resolver outputs that supply `@parent` fields as well as resolver inputs that traverse them. The profile also enables sometimes-passive generation and uses exact occurrence IDs to count source-supplied active fields whose skipped standard resolver has parent input demand; their maximum parent-demand depths are reported, and at least one such occurrence is a hard aggregate requirement. Runtime materialization and occurrence evidence produces per-slice and combined `HIT`/`MISS` reports plus, for each of nine parent-specific coverage criteria, the number of slices that completely hit it and the number of generated cases that contributed any evidence, both combined and by slice. Individual-slice misses remain diagnostic, but a combined miss across all four slices fails the task; semantic correctness, application accounting, binding validation, and forbidden direct variables beneath parent selections remain independent hard assertions. Deterministic contract tests separately require a resolver reached beneath one parent to demand its own parent while consuming a Query-fragment argument variable from each of `FromArgument`, `FromObjectField`, and `FromQueryField`.

Every generated case checks exact attempted/resolved/completed accounting, root-and-path-qualified application identities, `correctResolution`, and independently reconstructed from-field bindings. Sometimes-passive occurrences form the independently counted difference between registered result occurrences and standard resolver applications. Ordinary broad profiles require general sometimes-passive generation and activation but do not require its incidental intersection with parent demand; that stronger obligation belongs to the random-parent-focused profile, whose generator provides a dedicated witness field. A profile's aggregate run must also observe its required Resolution structural signatures.

The checked-in campaign uses fresh JVM rounds and persisted seeds distributed across schema breadth, registry diversity, query interactions, and large/deep worlds. Large/deep worlds bound generated list fanout so the budget explores depth instead of combinatorial list multiplication. Run persisted rounds with:

```shell
core/engine/runtime2/run-property-test-campaign.sh \
  classpath:/viaduct/engine/runtime2/property-tests/campaigns/resolution-broad-campaign-v1.json \
  21
```

The campaign script builds the standalone property-test launcher once and invokes the serialized campaign directly; it does not run one Gradle or JUnit invocation per round.

Replay one profile or exact coordinate from that round with:

```shell
./gradlew :core:engine:runtime2:resolutionBroadStressCampaign \
  -PresolutionBroadStressCampaignRound=21 \
  -PresolutionBroadStressCampaignProfile=multiple-owners \
  -PresolverPropertyCase=18:4:1
```

Coordinate replay suppresses aggregate structural-coverage requirements while preserving the recorded profile, seed, and generator dimensions. A failing generated case should be reduced to a small deterministic regression test after determining whether the defect belongs to the generator, an independent oracle, or Resolution.

## Extending The Suite

Add a scenario to the narrowest existing feature contract when every implementation claiming that feature must pass it.

Create a feature contract when the scenario establishes a distinct capability with a different support matrix. Create a policy mixin when it establishes an implementation choice shared across feature scopes.

Keep implementation-specific witness, mutation, depth, and stress tests separate when their assertions intentionally exceed the shared capability.

## Observations And Exact Witnesses

Configure `SharedOperationContext.resolverObserver` for resolver execution and `SharedOperationContext.checkerObserver` for checker execution. `TestWorld`, `FieldValueResolver`, and checker relations do not install hidden observation hooks. Replay of deterministic relations emits no runtime events.

| Observation | Emission boundary | Evidence supplied |
| --- | --- | --- |
| `onQueryOERPrepared` | After joint object/Query closure and before dispatch | Associates an object occurrence with its singular associated Query OER, including owners that later fail before input materialization. |
| `onResolverInvocation` | Immediately before ordinary or reference-target `FieldValueResolver.invoke`, after inputs are ready | Records the field, materialized object and Query inputs, instantiated selections, grounded arguments, optional selective demand, logical Query root, path, and occurrence identity. Attempts that later fail or cancel remain recorded. |
| `onQueryFragmentPrepared` | After Query-rooted input preparation and before dispatch | Associates a nonempty declared Query fragment with its owner-local projection; independently rooted reference execution remains distinct from an associated Query OER. |
| `onRootFieldReferenceInvocation` | After reference-target preparation returns | Associates the publication root and path with the reference descriptor, fresh invocation root, invocation key, and supplied demand. It is an association witness, not an outcome event. |
| `CheckerObserver.onCheckerInvocation` | Immediately before a field or type checker relation is invoked | Records checker kind, logical Query root, exact occurrence path, field arguments where applicable, checked target, and actual named raw object and Query projections. |
| Depth-first `onTaskStarted` hook | Immediately before Resolver06–08 run a dequeued orchestration or resolver task | Supplies ordering evidence for work-queue contracts. Recursive declared-Query work uses its separate depth-first driver and does not pass through this hook. |

All maintained families emit resolver observations. Resolver21–23 and production Resolution additionally emit checker observations. Callbacks may run concurrently and have no global ordering guarantee; payload identity and documented per-event timing are the contract.

`CorrectnessResolverObserver` supplies a set of resolver invocation IDs plus duplicate-preserving Query-root and reference-hop records. `CheckerApplicationRecorder` supplies duplicate-preserving checker attempts. Narrow tests may implement the interfaces directly when they need only counts, arguments, demand, or mutation evidence. Generated cases choose full fingerprint witnesses or count-only recording explicitly; supplied-demand capture remains an independent opt-in witness.

Observation proves only what its event records. Resolver events do not record outputs or final outcomes, checker events do not record results, and neither event stream is a scheduler trace. Correctness replay, exact application multisets, from-field binding validation, lifecycle assertions, activation coverage, and supplied-demand checks remain separate judgments.

## Checker Profiles

Resolver23 profiles establish the grounded checker boundary, while production Resolution adds symbolic variables, occurrence identities, and runtime checker inputs. Both levels retain the shared correctness oracles and add checker-specific exactness and activation requirements.

### Field-Checker Validation

`SymbolicFieldCheckerTest`, `FieldCheckerClosureTest`, and `FieldCheckerLifecycleTest` add four-forest closure, raw-to-checked boundaries, distinct symbolic keys with equal arguments, pair-local providers, aliases with physical overlap, null/error provider paths on both roots, independent raw `ctx.query()` scopes, cancellation before entry, provider exceptions, and bounded nontermination. `InclusionConditionTest` also requires terminal absent-checker publication when activation fails. `RequestScopeOwnershipTest` admits checker dispatch only from the two coroutine orchestration implementations.

`FieldCheckerGeneratedTest` explicitly adopts the generated checker contract and the Resolution correctness/permutation policies. Runtime success, denial, and mixed profiles require activated `FromArgument`, `FromObjectField`, `FromQueryField`, `FromProvider`, and symbolic-key signatures alongside the grounded profile obligations. Passive and root-reference profiles retain directed activation. Each observed execution is checked by checker-aware `correctResolution` and a separate duplicate-preserving checker-application multiset. Runtime inclusion and alias conditions remain deterministic witnesses.

The runtime checker distributions use `ResolverFragmentDepth=1`: two independently bound named pairs can multiply symbolic dependency trees, so copying depth-two fragments at every registered coordinate produced a case with over 129,000 resolver invocations before the 15-second request bound. This limit bounds random workload construction without changing request timeouts, per-case oracles, or required activation signatures. Fixed parent spines still exercise multilevel parent demand, and deterministic contracts retain nested fragment and named-provider combinations. The grounded Resolver23 distributions retain depth two.

Run the replayable stress task from the repository root: `./gradlew :core:engine:runtime2:resolutionFieldCheckerStress -PresolutionFieldCheckerStressSeed=424242`. Its default size is `50:5:10` (2,500 cases), its profile defaults to `success`, and `-PresolutionFieldCheckerStressProfile=denial` selects denial. It also accepts `mixed`, `passive`, and `root-reference`, and a `-PresolutionFieldCheckerStressSize=<schemas>:<registries>:<queries>` override. Set `-Pviaduct.resolution.threadcount=100` for concurrent execution with the same oracle and exactness assertions. Failures use the ordinary `resolverPropertyReplay` task with the reported `resolution-field-checker-*` profile, seed, size, and coordinates.

### Grounded Type-Checker Profiles

Resolver23 composes `GeneratedTypeCheckerContract` with replayable `resolver23-type-checker-success`, `resolver23-type-checker-denial`, and `resolver23-type-checker-mixed` profiles. Each uses the ordinary 150-case profile budget, honors the configured property-test seed and size, and randomizes schemas, registries, queries, sometimes-passive output, and mixed field checkers. Type checkers read duplicate named raw object/Query projections plus an empty pair. Object inputs contain typename, passive scalar fields, and eligible scalar value resolvers; Query inputs contain typename and eligible scalar value resolvers. A conservative transitive type-dependency order keeps the augmented graph acyclic while admitting resolver inputs that themselves demand field and type checks. [Property testing](property-tests.md#generated-worlds) describes the generated-world boundary.

Aggregate guards require observed type-checker applications, success/denial as applicable, list elements, associated Query roots, repeated concrete types, field/type checks sharing a path, and active value resolvers on both raw input roots. They additionally require active resolver inputs on list and associated-Query occurrences, successful/denying owners as applicable, and actual field/type checks selected by those value resolvers' checked inputs. Coverage joins declared input selections to invocation records by Query-root identity and the entire physical path, including list indices. Registry presence or an invocation at another occurrence does not establish activation; negative tests remove or move the resolver invocation evidence and require those signatures to disappear. Counts report contributing ordinary cases, while correctness and duplicate-preserving checker application accounting validate both ordinary and permutation-equivalent executions.

Completed-result correctness independently replays the value and checker relations and compares every recorded named checker input against reconstructed raw object and Query projections. Generated exactness starts with the requested client selections and follows registered resolver and checker fragments. Replayed source ownership distinguishes passive fields from independently demanded resolver occurrences; only those resolver occurrences contribute checked input demand. Observed resolver invocations, checker-result slots, and checker-invocation records do not seed expected demand. Source replay uses the correctness relation and its shared Query caches to reconstruct producer values. Orchestration observations locate each object's associated Query root even when an owner fails before input materialization. An owner's effective inclusion condition is evaluated before expanding its inputs, including when symbolic arguments become errors before invocation. It preserves structural ancestor identity, list positions, runtime conditions, and the distinction between associated Query roots and independently executed reference Query roots. A separate slot-to-invocation multiset comparison retains missing/duplicate observation coverage and rejects non-null field-check results without a registered checker, including raw input slots. Checked-selection correctness also rejects such results directly. Mutation tests reject surplus raw-only type checks, type checks justified only by surplus resolver invocations, and equal-but-wrong scalar projections even when the checker relation returns the same success result. Runtime activation establishes that the declared dependency was invoked at the right occurrence, without claiming that the checker was its sole demander. The generated distribution bounds raw checker selections to scalar fields and omits Query type checks, parent/reference interactions, and deliberate wait cycles; deterministic T4 contracts retain those broader cases.

Resolution type profiles and their exact replay and stress commands are documented in [Runtime Type-Checker Campaigns](resolution.md#runtime-type-checker-campaigns). The production distribution retains bounded scalar type inputs and adds sampled object/Query fragment pairs with type-owned from-path and callback-provider variables. Callback-provider bindings consumed on each input root, both path source roots, variable use in both input fragments, and error-valued arguments in type-checker inputs must activate in every randomized profile. Nested path bindings have directed deterministic coverage in the ordinary check and become a mandatory randomized activation in size-overridden stress profiles. Resolver argument errors retain their normal generation weight; checker variable definitions are restricted to uses that survive argument-error materialization. Coverage joins completed symbolic input cells and bindings to the exact invoked type-checker target, Query root, and occurrence path; negative tests reject declarations without invocation or completed bindings. Correctness replay independently checks path values and the provider relation, and duplicate-preserving checker accounting checks application identity. `RuntimeTypeCheckerWitnessTest` retains the fixed provider/exclusion example as a separate deterministic regression.

The dedicated `resolutionMultithreadedStress` task also runs all three randomized production type-checker profiles, with their mixed field checkers, correctness and exactness oracles, provider/path activation guards, and query permutations. It defaults to 100 resolution threads and requires observed continuation overlap and multiple worker threads for each checker profile. Broad campaign runs remain checker-free; the type-checker profiles supply the separate concurrent checker evidence.
