# Access-Check Semantics

This document defines Runtime2's access-check semantics. It specifies the obligations shared by the coroutine resolver families and identifies the deliberate capability boundaries of the depth-first families.

The examples below specify the schema, value resolvers, checker registrations, required selections, and returned values. Nothing about a field's ownership, value, or access policy is left implicit.

## Terminology

- A **field occurrence** is one selection of one field at one object-result occurrence, including its grounded arguments.
- The **base type** of a GraphQL type expression is the type left after removing all list and non-null wrappers.
- A **base cell** is the cell left after following all list wrappers in an engine result. An object-valued base cell points to an `ObjectEngineResult` occurrence.
- An **object occurrence** is one `ObjectEngineResult` identified by its rooted result-tree occurrence. Ordinary object-valued base cells receive fresh object occurrences; a structural backedge such as `@parent` points to an existing occurrence rather than creating another one.
- **Resolving a check** means running a checker and publishing its `CheckerResult`.
- **Enforcing a check** means allowing a `CheckerResult.Error` to prevent a consumer from reading the corresponding value. Resolution and enforcement are separate operations.
- An **unchecked read** reads the raw value without enforcing its checker results. It does not mark the value, resolver, or descendant work as permanently unchecked.

## Logical Access-Check Results

Access-check resolution produces two independently owned kinds of result:

1. A field-checker result belongs to one field-cell occurrence.
2. A type-checker result belongs to one concrete `ObjectEngineResult` occurrence.

A checker-result value of `null` means that no checker applies. Every field-cell occurrence and concrete OER occurrence has a checker-result promise; an unfinished promise denotes expected work, while a completed null is the terminal no-check result. Promise placement does not determine checker-application identity.

Field and type results remain distinct while they are resolved. A consumer enforcing a field access reads the field result from the containing cell and, when the selected base value is an object, the type result from the reached object occurrence. It combines the applicable results only at enforcement time. If both are errors, the shared Engine API `CheckerResult` contract determines how they combine; the engine must not invent an independent generic precedence rule.

### Failure Isolation And Waiting

The [tenant failure and progress policy](principles.md#isolate-tenant-failure-without-stranding-work) applies to checkers, their variables providers, and the value resolvers supplying their inputs. A slow or hanging callback may delay or hang the whole operation, even after another failure makes its output unnecessary. For example, if a checker's variables provider throws while a source resolver for a from-path variable is still running, execution may wait for that source even when this checker was its only demander. Prompt cancellation or retraction of that demand is not required. The failure must still be published at its owned checker/binding boundary and enforced by the existing consumer rules; aborting the whole request is not an acceptable fallback, and a failed or unfinished check cannot grant access. This policy does not change the combination of completed `CheckerResult.Error` values or excuse missing terminal publication after a producer exits or is bypassed.

## Field Checks

A field checker belongs to a field occurrence, not to a field resolver invocation. A checker therefore applies whether the checked value was produced actively by that field's registered resolver or supplied passively by an ancestor producer.

Selecting a passive checked field must still create enough demand to run its checker. Conversely, a checker required selection can create value demand: if the checker selects an active field, that field's value resolver must run so the checker can receive its raw value.

### Resolver-Family Boundary

Access checks are implemented only by the coroutine resolver families. [Resolver01–08](../../impldocs/architecture/resolver-families.md#comparison-grid) could execute the restricted case of a checker with no object- or Query-rooted required selections, but that capability would not extend to the intended semantics. Checker-required selections introduce unchecked value-demand edges, while any value resolver reached through such an edge evaluates its own dependencies as ordinary checked demand. Those edges can cross object occurrences and their associated Query OERs and need not fit the fixed local dependency order used by the recursive and queued depth-first families.

Supporting that general readiness graph in Resolver01–08 would require occurrence-aware suspension, promise readiness, or graph re-entry—the machinery that distinguishes the coroutine families. Runtime2 therefore keeps Resolver01–08 value-only rather than exposing a dead-end no-RSS access-check subset. Resolver21–23 are the maintained comparison models for the access-check design, and production Resolution implements the complete Runtime2 semantics. This is an intentional architecture boundary, not a claim that every restricted checker program is impossible to execute depth-first.

Resolver01–08 require a checker-free resolver registry as an input precondition: `fieldChecker(field)` returns null for every field in their reasoning world. Shared construction-demand closure may consult the registry directly; absent checker registrations contribute no checker demand, so no checker-capability flag or filtered registry view is needed. This defines the supported input domain; it does not require runtime validation of the precondition.

## Type Checks And Object Occurrences

A type checker executes once for each concrete object occurrence of the checked type. List wrappers are significant because their object-valued base cells normally point to distinct object occurrences. Equal schema types, values, node IDs, or selections do not merge those occurrences. Reaching an existing OER through a structural backedge does not create another type-checker application.

An `@parent` relationship cannot return to Query or a `@namespaceType`: its paired child producer must belong to an ordinary object type. In particular, checked parent demand cannot reach an orchestration's associated Query root. Resolver and checker Query fragments demand fields within that root without demanding its type check, so the associated Query OER has a null type-checker result. Ordinary checked execution roots retain their existing type-check demand.

For example, consider this complete schema and execution world:

```graphql
type Query {
  users: [[User]]!
}

type User {
  id: ID!
}
```

The registered `Query.users` resolver has no required selections and returns:

```text
[
  [User { id: "1" }, User { id: "2" }],
  [User { id: "3" }]
]
```

No field checker is registered for `Query.users` or `User.id`. A type checker is registered for `User`; it requires the object-rooted selection `id` and grants access exactly when the ID is not `"2"`.

The client asks for:

```graphql
query {
  users {
    id
  }
}
```

There are three distinct `User` OER occurrences, one reached through each object-valued base cell, so the `User` type checker runs three times: once for each returned object, not once for `Query.users`, once per list wrapper, or once per selected `User` field. The checker for the second occurrence denies access only to that `User` occurrence; ordinary GraphQL null propagation then follows the declared nullable list and element wrappers.

This per-OER behavior is why type checks are first-class results rather than field checks copied onto every field of the type. Copying a type checker onto fields would repeat the same logical decision for every selected field and would fail to represent an object occurrence that must be checked even before choosing a particular child field.

## Checker Required Selections

A field or type checker declares a named map of input-fragment pairs. Each named input contains one materialization template rooted at the checked field's containing object, one rooted at `Query`, and the variable definitions and optional variables provider shared by those two templates. Either template may be empty. The checker receives both materialized values for every name, matching the object/Query input pair supplied to a field resolver. Variables derived from either root are therefore available to selections in either member of the pair.

Construction unions the selections independently within each root: the containing occurrence is extended with the union of every named pair's object-rooted selections, and its orchestration's associated Query OER is extended with the union of every resolver and checker owner's nonempty Query-rooted selections. Materialization does not lose the owner or named-pair boundaries. Each pair is materialized independently, and the checker receives a name-to-pair map containing its separate object and Query projections. An empty Query template produces an empty Query-rooted value without demanding fields in the associated Query OER.

This paired contract is intentionally different from the existing `CheckerExecutor` SPI, whose named values are singular required selection sets. The production dispatcher adapter compiles normalized `FromArgument`, `FromObjectField`, `FromQueryField`, and no-RSS function-provider declarations into each pair. It recursively retains the nested selection templates needed by supported from-field declarations and represents execution conditions as provider-backed inclusion guards. It remembers whether the object or Query member was the outer RSS and passes only that materialized member back to the SPI checker. Arbitrary opaque variables providers with their own RSS cannot be converted by the dispatcher and remain unsupported. Runtime2 registry construction, demand closure, scheduling, and materialization use the paired model rather than preserve the outer-RSS distinction internally. [Engine API integration](../integration/engine-api.md#required-selection-variables) owns the current conversion boundary.

The associated Query OER is a logical occurrence boundary owned by the containing orchestration. Resolver and checker occurrences in that scope share it, including exact-key value production, but materialize owner-local projections. It does not require an implementation to forgo safe physical batching across scopes, but work from the primary operation's Query OER or another containing occurrence's Query OER must not be substituted as though it had the same occurrence identity.

Consider this complete world:

```graphql
type Query {
  viewerId: ID!
  records: [Record!]!
}

type Record {
  ownerId: ID!
  secret: String
}
```

The registered `Query.viewerId` resolver has no required selections and returns `"viewer"`. The registered `Query.records` resolver has no required selections and returns two passive records, `Record { ownerId: "viewer", secret: "first" }` and `Record { ownerId: "other", secret: "second" }`. A field checker is registered for `Record.secret`. Its named `accessInputs` pair contains the object-rooted selection `{ ownerId }` and the Query-rooted selection `{ viewerId }`. It grants access exactly when `accessInputs.object.ownerId` equals `accessInputs.query.viewerId`.

The client asks for:

```graphql
query {
  viewerId
  records {
    secret
  }
}
```

This execution has one primary Query OER for the client operation and two additional Query OERs, one associated with each passive `Record` occurrence's orchestration. Each `Record.secret` checker uses its containing record occurrence's associated Query OER; neither shares the primary Query OER or the other record occurrence's Query OER. Consequently, this semantic model contains three distinct `Query.viewerId` resolver occurrences even if a later execution layer can physically coalesce some underlying work. Multiple checker or resolver owners within either one record occurrence would instead share that occurrence's associated Query OER.

## Where Checks Are Enforced

The consumer of a value determines whether its checker results are enforced:

| Consumer | Enforces checks on its selected value? |
| --- | --- |
| GraphQL response completion | Yes |
| Ordinary value-resolver input materialization | Yes |
| Checker input materialization | No; it reads raw values |

For ordinary fields, resolving a value and resolving its field checker normally proceed concurrently. The field checker does not gate whether the value resolver starts. Type-checker resolution begins once the value produces a concrete OER and belongs to that OER's orchestration. At enforcement, a singular object access combines its containing cell's field result with that OER's type result; a list access enforces the field result on the containing field and the type result of each reached object occurrence. Mutation payload fields retain these ordinary semantics. Runtime2 rejects field and type checkers in the ordered mutation namespace and does not support subscriptions. The [`runtime`](../../../runtime) engine's checker-first invocation policy for top-level mutation and subscription fields is outside the current Runtime2 surface.

Applicable checker denial takes precedence over a raw-value error at a checked consumer; the denied value is not exposed even when producing it also failed. Checker inputs still read the raw error because they do not enforce checks. Field-checker enforcement on `@parent` backedges and reference target paths remains an [Engine API integration gap](../integration/engine-api.md#supported-alpha-surface); model contracts for those paths do not establish adapter support.

For an ordinary value resolver, a denial in an object- or Query-rooted input is represented like any other error-valued input. The denial does not suppress the resolver invocation or force its result to fail merely because the field was declared in a required selection set. Reading the denied selection propagates its policy error; a resolver that does not read that selection can still produce a value. The denied raw value is never exposed.

### Checkers Read Raw Values

Access-check executors are not themselves subject to access checks. Their required selections read value slots without enforcing field- or type-checker results. Checker-origin demand therefore requires the selected raw values and any active value resolvers needed to produce them, but it does not require checker execution for coordinates selected only on behalf of that checker.

This rule prevents a checker from waiting on itself directly or indirectly through the access slots of its inputs. It is a property of the checker's input-materialization edge, not a transitive execution mode.

Consider this complete world:

```graphql
type Query {
  record: Record!
}

type Record {
  protected: Int
  derived: Int
  dependency: Int
}
```

The registered `Query.record` resolver has no required selections and returns `Record { protected: 7, dependency: 40 }`; `protected` and `dependency` are therefore passive values in this occurrence. The registered `Record.derived` resolver requires the object-rooted selection `dependency` and, if it receives that value, returns `dependency + 1`.

Three field checkers are registered:

- The `Record.protected` checker requires the object-rooted selection `derived` and grants access exactly when `derived` is `41`.
- The `Record.derived` checker always denies access.
- The `Record.dependency` checker always denies access.

The client asks for:

```graphql
query {
  record {
    protected
  }
}
```

The `protected` checker creates checker-origin demand for `derived`. That demand launches the `derived` value resolver but does not enforce or, in the absence of any other demand, require resolution of the `derived` field checker. The `derived` resolver is nevertheless an ordinary value resolver: its own selection of `dependency` is resolver-origin demand, so the `dependency` checker is resolved and enforced. The denial on `dependency` prevents `derived` from producing `41`, and the `protected` checker cannot obtain its required value.

Thus a checker can directly read an unchecked active field, but the active field's resolver still reads its own dependencies under normal access checks. “Checker inputs are unchecked” is not transitive.

## Resolver-Specific Applicability

`CheckerResult.Error.isErrorForResolver(CheckerResultContext)` owns the decision whether a checker error applies to an ordinary resolver consumer. `CheckerResultContext.fieldDirectives` is a generic optional bridge for an execution integration to expose directives from the consuming field selection; neither the OSS checker API nor Runtime2 assigns built-in meaning to a particular directive name.

Runtime2 materialization selections retain the generic `FieldDirectives` context from each source field occurrence and pass it to `isErrorForResolver`; Runtime2 neither names nor interprets a policy directive. The test-fixture parser accepts no-argument custom directives declared only on `FIELD`, which is sufficient to model Airbnb's `@bypassPolicyCheck`: a service-defined checker error may recognize that spelling while an otherwise identical checker may ignore it. Co-applicable occurrences collected under one response key expose a directive only when every occurrence exposes it, preventing one annotated occurrence from weakening an unannotated occurrence. GraphQL response completion remains a distinct consumer and enforces combined checker errors directly.

## Demand Provenance Is Semantically Relevant

The same selected coordinate can require different work depending on why it was selected:

- Resolver- or client-origin demand requires the raw value and applicable checker results because those consumers enforce access.
- Checker-origin demand requires the raw value but does not, by itself, require checker results for that selected coordinate.
- If an active value resolver is launched by checker-origin demand, the resolver's own required selections introduce new resolver-origin demand and therefore use normal checked semantics.

During demand closure, the implementation must distinguish unchecked checker demand from checked resolver or client demand. Once closure reaches a fixed point, it may discard the derivation history as long as its result separately records the required value and checker slots. Materialization independently preserves whether each consumer performs raw or checked reads.

### Type-Check Demand Is a Cached Occurrence Invariant

For each concrete object occurrence, demand closure maintains the following invariant:

```text
typeCheckDemanded(O) =
  O is an ordinary checked resolution root
  or some checked field occurrence demands O as an object-valued base result
```

This bit is a cached consequence of demand, not an independent access-policy decision. If it is true and the concrete type has a registered checker, that checker must resolve exactly once for the occurrence; without a registered checker, the demanded result is the terminal no-check result. Enforcement remains a separate operation performed only by checked consumers.

The bit cannot be derived from the checked selections rooted inside `O`. Those selections describe checked field reads *within* the occurrence, whereas `typeCheckDemanded` records checked demand for the occurrence *from outside* it. A checked parent field can demand `O`'s type check even when `O` has no applicable checked child selections. Conversely, a raw checker input can reach `O`, activate a value resolver, and cause that resolver's own inputs to appear as checked fields inside `O` without demanding `O`'s type check.

Ordinary checked resolution establishes the root bit at entry. Descending through a field establishes the child's bit from the checked provenance of that field. Combining demand for the same occurrence joins the bits with logical OR, so any checked incoming demand suffices. Local closure may add checked or unchecked fields within the occurrence but preserves the bit.

Resolution additionally retains `Demand.typeCheckCondition`, the disjunction of effective conditions on checked incoming edges. This condition controls checker execution independently of the selected fields inside the OER or the activation of any one containing cell. Raw and checked overlap can therefore construct an OER while leaving its type result null when every checked incoming edge is excluded. Grounded Resolver21–23 retain their existing producer-activation domain.

Selective successor-demand calculation retains the same provenance only while finding producer-facing values. A selected field contributes its checker inputs only when that occurrence is checked; any active value resolver reached from either checked or unchecked demand contributes its own inputs as checked demand. The final tenant-facing `SelectionForest` is the union of required values and contains no checker-slot concept. A structurally present or over-returned field is not thereby checked, and overlap between unchecked checker demand and independent checked demand produces one value and one applicable checker application for the occurrence.

## Exact Checker Applications

Access-result correctness and access-check execution exactness are complementary judgments. `correctResolution` requires registered checker results for checked client and resolver-input selections, follows applicable checker object fragments as unchecked value demand, validates each checker Query root as unchecked demand, reconstructs every named raw object/Query input pair, independently replays each named checker variables provider with its owning arguments (empty for type checkers) and compares every provided binding, and requires the replayed success/error variant to match the stored result; a checked occurrence at which no checker can run instead requires a null result. It also compares every recorded named checker input with its reconstructed raw projection, including response aliases and scalar values, and compares the object and Query values actually passed to field resolvers with replayed checked materializations, including access errors at exact Engine value locations. Associated Query roots have no incoming type-check requirement. An independently executed reference-target Query root does: its source-replayed reference requires the root type result and matching checker relation even if its field selections are all excluded or the result was already validated for a raw consumer. Checker errors expose no tenant-independent equality, so replay does not compare error instances. `correctResolution` may still tolerate additional value structure and does not establish checker execution counts. Exact access evidence records each attempted checker call by checker kind, logical Query root, occurrence path, optional grounded field arguments, and checked field or concrete-type target; comparison is duplicate-preserving so both omitted and repeated applications fail. Observation occurs after checker inputs are ready and immediately before checker invocation; its separate input payload retains named projections without recording a result or policy outcome. Generated type-application expectations start from requested selections and follow registered fragments and replayed source ownership. Only independently demanded resolver occurrences contribute checked inputs; observed resolver invocations and checker-result slots cannot justify additional demand. The owner's effective inclusion condition gates input expansion, including when symbolic arguments become errors before invocation.
