# Access-Check Semantics

This document records the access-check behavior that qplan is intended to model. It describes semantic obligations rather than the implementation status of any resolver version. In particular, an incremental implementation may resolve checker-result slots before it begins enforcing those results during input materialization or GraphQL completion.

The examples below specify the schema, value resolvers, checker registrations, required selections, and returned values. Nothing about a field's ownership, value, or access policy is left implicit.

## Terminology

- A **field occurrence** is one selection of one field at one object-result occurrence, including its grounded arguments.
- The **base type** of a GraphQL type expression is the type left after removing all list and non-null wrappers.
- A **base cell** is the cell left after following all list wrappers in an engine result. For type checking, the relevant base cells are those whose value is an object engine result.
- **Resolving a check** means running a checker and publishing its `CheckerResult`.
- **Enforcing a check** means allowing a `CheckerResult.Error` to prevent a consumer from reading the corresponding value. Resolution and enforcement are separate operations.
- An **unchecked read** reads the raw value without enforcing its checker results. It does not mark the value, resolver, or descendant work as permanently unchecked.

## Logical Cell Results

Each result cell logically has three independent results:

1. The value result.
2. The field-checker result for the field occurrence that produced the cell.
3. The type-checker result for the cell's base object, when its base type has a type checker.

A checker-result value of `null` means that no checker applies. This is different from an unpublished checker-result promise: `null` is a completed semantic result, while an unpublished promise is work that has not yet been claimed or completed.

Field and type results remain distinct while they are resolved. A consumer that enforces access must account for both. If both are errors, the production `CheckerResult` contract determines how they combine; the engine must not invent an independent generic precedence rule.

## Field Checks

A field checker belongs to a field occurrence, not to a field resolver invocation. A checker therefore applies whether the checked value was produced actively by that field's registered resolver or supplied passively by an ancestor producer.

Selecting a passive checked field must still create enough demand to run its checker. Conversely, a checker required selection can create value demand: if the checker selects an active field, that field's value resolver must run so the checker can receive its raw value.

### Resolver-Family Boundary

Access checks are implemented only by the coroutine resolver families. Resolver01–08 could execute the restricted case of a checker with no object- or Query-rooted required selections, but that capability would not extend to the intended semantics. Checker-required selections introduce unchecked value-demand edges, while any value resolver reached through such an edge evaluates its own dependencies as ordinary checked demand. Those edges can cross object occurrences and their associated Query OERs and need not fit the fixed local dependency order used by the recursive and queued depth-first families.

Supporting that general readiness graph in Resolver01–08 would require occurrence-aware suspension, promise readiness, or graph re-entry—the machinery that distinguishes the coroutine families. Qplan therefore leaves Resolver01–08 value-only rather than exposing a dead-end no-RSS access-check subset. Resolver21–23 stage the access-check design, and Resolver26 is its end-state implementation target. This is an intentional architecture boundary, not a claim that every restricted checker program is impossible to execute depth-first.

Resolver01–08 require a checker-free resolver registry as an input precondition: `fieldChecker(field)` returns null for every field in their reasoning world. Shared construction-demand closure may consult the registry directly; absent checker registrations contribute no checker demand, so no checker-capability flag or filtered registry view is needed. This defines the supported input domain; it does not require runtime validation of the precondition.

## Type Checks And Base Cells

A type checker applies to each object-valued base cell of the checked type. List wrappers are significant because they can contain multiple such base cells.

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

There are three `User` base cells, so the `User` type checker runs three times: once for each returned object, not once for `Query.users`, once per list wrapper, or once per selected `User` field. The checker for the second base cell denies access only to that `User` occurrence; ordinary GraphQL null propagation then follows the declared nullable list and element wrappers.

This per-base-cell behavior is why type checks are first-class results rather than field checks copied onto every field of the type. Copying a type checker onto fields would repeat the same logical decision for every selected field and would fail to represent an object occurrence that must be checked even before choosing a particular child field.

## Checker Required Selections

A field or type checker declares a named map of input-fragment pairs. Each named input contains one materialization template rooted at the checked field's containing object, one rooted at `Query`, and the variable definitions and optional variables provider shared by those two templates. Either template may be empty. The checker receives both materialized values for every name, matching the object/Query input pair supplied to a field resolver. Variables derived from either root are therefore available to selections in either member of the pair.

Construction unions the selections independently within each root: the containing occurrence is extended with the union of every named pair's object-rooted selections, and its orchestration's associated Query OER is extended with the union of every resolver and checker owner's nonempty Query-rooted selections. Materialization does not lose the owner or named-pair boundaries. Each pair is materialized independently, and the checker receives a name-to-pair map containing its separate object and Query projections. An empty Query template produces an empty Query-rooted value without demanding fields in the associated Query OER.

This paired contract is intentionally different from the existing production `CheckerExecutor` SPI, whose named values are singular required selection sets. That SPI is a legacy integration boundary, not the qplan semantic model. Its Airbnb implementations currently nest a variable RSS at most once in practice, so an adapter can translate each singular outer RSS and its optional nested dependency into one pair, remember whether the object or Query member was the legacy outer RSS, and pass only that materialized member to the legacy checker. A replacement checker API will consume the pair directly. Qplan's registry, demand closure, scheduling, and materialization should use the paired model rather than preserve the legacy outer-RSS distinction.

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

For query fields, resolving a value and resolving its checker normally proceed concurrently. The checker does not gate whether the query field's value resolver starts. A checked consumer evaluates the combined checker results first: an applicable denial can complete the consumption without awaiting or consulting the raw value, while a successful or consumer-inapplicable result proceeds to that value. For top-level mutation and subscription fields, production instead runs the field checker first and does not start the value resolver when that field check denies access.

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

`CheckerResult.Error.isErrorForResolver(CheckerResultContext)` owns the decision whether a checker error applies to an ordinary resolver consumer. `CheckerResultContext.fieldDirectives` is a generic optional bridge for an execution integration to expose directives from the consuming field selection; neither the OSS checker API nor qplan assigns built-in meaning to a particular directive name.

Qplan's materialization selections retain the generic `FieldDirectives` context from each source field occurrence and pass it to `isErrorForResolver`; qplan neither names nor interprets a policy directive. The test-fixture parser currently accepts no-argument custom directives declared only on `FIELD`, which is sufficient to model Airbnb's `@bypassPolicyCheck`: a service-defined checker error may recognize that spelling while an otherwise identical checker may ignore it. Co-applicable occurrences collected under one response key expose a directive only when every occurrence exposes it, preventing one annotated occurrence from weakening an unannotated occurrence. GraphQL response completion remains a distinct consumer and enforces combined checker errors directly.

## Demand Provenance Is Semantically Relevant

The same selected coordinate can require different work depending on why it was selected:

- Resolver- or client-origin demand requires the raw value and applicable checker results because those consumers enforce access.
- Checker-origin demand requires the raw value but does not, by itself, require checker results for that selected coordinate.
- If an active value resolver is launched by checker-origin demand, the resolver's own required selections introduce new resolver-origin demand and therefore use normal checked semantics.

During demand closure, the implementation must distinguish unchecked checker demand from checked resolver or client demand. Once closure reaches a fixed point, it may discard the derivation history as long as its result separately records the required value and checker slots. Materialization independently preserves whether each consumer performs raw or checked reads.

Selective successor-demand calculation retains the same provenance only while finding producer-facing values. A selected field contributes its checker inputs only when that occurrence is checked; any active value resolver reached from either checked or unchecked demand contributes its own inputs as checked demand. The final tenant-facing `SelectionForest` is the union of required values and contains no checker-slot concept. A structurally present or over-returned field is not thereby checked, and overlap between unchecked checker demand and independent checked demand produces one value and one applicable checker application for the occurrence.

## Exact Checker Applications

Access-result correctness and access-check execution exactness are complementary judgments. `correctResolution` requires registered checker slots for checked client and resolver-input selections, follows claimed checker object fragments as unchecked value demand, validates each checker Query root as unchecked demand, reconstructs every named raw object/Query input pair, and requires the replayed success/error variant to match the stored slot; a checked occurrence at which no checker can run instead requires a null slot. It also compares the object and Query values actually passed to field resolvers with replayed checked materializations, including access errors at exact Engine value locations. Checker errors expose no tenant-independent equality, so replay does not compare error instances. `correctResolution` may still tolerate additional value structure and does not establish checker execution counts. Exact access evidence records each attempted checker call by checker kind, logical Query root, occurrence path, grounded arguments, and checked coordinate; comparison is duplicate-preserving so both omitted and repeated applications fail. Observation occurs after checker inputs are ready and immediately before checker invocation, and records no result or policy outcome.
