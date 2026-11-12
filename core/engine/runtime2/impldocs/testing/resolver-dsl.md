# Resolver Test DSL

## Scope

The resolver-test DSL describes small deterministic resolver worlds for regression tests and counterexamples of failed resolution. A world embeds its schema, resolver dependencies, variable definitions, and resolver outputs in one GraphQL document. It does not replace tests of fixture composition, generated worlds, scheduling, tracing, or other internal execution mechanics.

Construct a world with `TestWorld.fromDSL(schemaSDL)`.

## Schema Presentation

Present resolver worlds top-down so a reader can follow them in the same order as resolution:

1. Start with `extend type Query`.
2. Define the types named by those root fields.
3. Continue with the types reached from those definitions.

Apply the same ordering to test fixtures, documentation examples, and counterexamples shared during design discussions. GraphQL does not require this declaration order; it is a readability convention for this DSL.

## Built-In Schema

Every DSL world includes:

```graphql
interface Node {
  id: ID!
}

type Query {
  node(id: ID!): Node
}
```

Test schemas add root fields with `extend type Query`. Concrete Node implementations still declare `id: ID!` as required by GraphQL interface validation.

The fixture parser also recognizes these metadata definitions:

```graphql
directive @nodeResolver(result: [NodeResult!]!) on OBJECT

input NodeResult {
  id: ID!
  result: JSON
}

directive @resolver(
  of: String
  qf: String
  pathVars: [VariableDefinition!]! = []
  providerVars: JSON
  vars: VariablesProvider
  result: JSON
  script: String
) on FIELD_DEFINITION

input VariableDefinition {
  name: String!
  path: [String!]!
}

input VariablesProvider {
  names: [String!]!
  script: String!
}
```

Exactly one of `result` or `script` must be present. `result: null` remains an explicit literal result; `script` must be a string. The compiler reads the directives from the source AST, builds the ordinary resolver registry, and strips the directives before constructing the retained GraphQL schema. The model schema therefore does not acquire a JSON leaf type or DSL metadata.

## Field Resolvers

`of` is an optional selection-set string relative to the field's parent object type. An absent or empty `of` denotes an empty object fragment. Response-key aliases are preserved in the resolver-visible input. Co-applicable selections sharing one response key must identify a compatible field invocation.

The string literal `"ERROR"` in an argument position produces `ArgumentResolutionError`, which collapses the enclosing tuple to `Arguments.Error`. It is fixture syntax, not a schema-defined string value or resolver variable.

```graphql
extend type Query {
  item: Item!
    @resolver(result: {source: 7})
}

type Item {
  source: Int!
  computed: Int!
    @resolver(
      of: "source dependency(arg: \"ERROR\")"
      result: "sumplus1(source)"
    )

  dependency(arg: Int!): Int!
    @resolver(result: 1)
}
```

Every variable used by `of` or `qf` is defined exactly once, including names shared by both fragments:

- A variable whose name matches an argument of the resolver field is inferred as `FromArgument`.
- A name in `pathVars` is compiled as `FromObjectField`.
- A name declared by `vars.names` or in the legacy `providerVars` object is compiled as `FromProvider` and returned by the resolver's one variables-provider function.
- Provider names and `pathVars` names may not shadow field arguments or each other.
- Unused, missing, and duplicate variable definitions are rejected.

```graphql
extend type Query {
  result(seed: Int!): Int!
    @resolver(
      of: "source consume(value: $fromSource)"
      pathVars: [{name: "fromSource", path: ["source"]}]
      result: "sum(consume, $seed)"
    )

  source: Int! @resolver(result: 7)

  consume(value: Int!): Int!
    @resolver(result: "sumplus1($value)")
}
```

Using `$seed` in a result expression reads the field argument directly and does not define a registry variable. Only a use inside `of` or `qf` creates an inferred `FromArgument` definition.

`pathVars.path` follows Runtime2's `FromObjectField` restrictions. In particular, an intermediate path component cannot cross a list. This restriction is unrelated to field paths in result expressions.

`providerVars` must be a nonempty object when present. Its values are GraphQL literals; string values matching the ordinary `value`, `sum`, or `sumplus1` expression syntax are evaluated when the provider runs and may reference resolver arguments. The provider is invoked once for each active resolver occurrence after that occurrence's arguments are grounded. The string `"ERROR"` is reserved as a provider-failure sentinel.

```graphql
extend type Query {
  result(seed: Int!): Int!
    @resolver(
      of: "consume(value: $provided)"
      providerVars: {provided: "sum($seed, $seed)"}
      result: "sum(consume)"
    )

  consume(value: Int!): Int!
    @resolver(result: "sum($value)")
}
```

`vars` supplies a scripted variables provider and is mutually exclusive with `providerVars` on one resolver. Its `names` must be a nonempty list of unique GraphQL names used in `of` or `qf`. The script runs once per active grounded resolver occurrence and returns one map with exactly those keys. Either provider form can accompany either `result` or `script`.

Provider scripts use the same JEXL configuration as field scripts. Each invocation receives fresh bindings for the field arguments by schema name, including null for absent optional arguments, and the reserved `error` constant. Provider scripts have no RSS inputs or `query` callable. Argument objects and lists are recursively read-only. Provider arguments and results support null, integers, Boolean, String (including IDs and enum names), Double, lists, and input-object maps. Integer values are normalized to GraphQL Int's signed 32-bit range; the existing engine validates the resulting values against the fragment argument types.

Returning `error` at the top level fails the provider, which fails the owning field and completes all its provider bindings with `VariableBinding.Error`. No variable receives an error as input data. A map or list containing `error` is rejected recursively, so use `seed < 0 ? error : {'double': seed * 2, 'constant': 3}`, never `{'double': error, 'constant': 3}`. Syntax and runtime diagnostics identify the `Type/field.vars` coordinate and script line; result-conversion failures identify the offending variable path.

```graphql
extend type Query {
  result(seed: Int!): Int!
    @resolver(
      of: "left: consume(value: $double) right: consume(value: $constant)"
      vars: {
        names: ["double", "constant"]
        script: "seed < 0 ? error : {'double': seed * 2, 'constant': 3}"
      }
      script: "objectValue.left + objectValue.right"
    )

  consume(value: Int!): Int! @resolver(script: "value")
}
```

## Query Fragments

`qf` is an optional selection-set string relative to `Query`. An absent or empty `qf` denotes an empty Query fragment. Like `of`, it preserves response-key aliases, nested selections, inclusion conditions, and resolver-owned variable bindings. `pathVars` still reads paths from the object fragment, even when the variable is used only by the Query fragment. This does not add Query-field variable providers.

Use `query:` before a response-key path in a result expression to read the materialized Query input. Unqualified paths continue to read the object input, and `$name` reads a resolver argument. The prefix is separate from GraphQL names so an object field named `query` remains usable.

```graphql
extend type Query {
  item: Item! @resolver(result: {local: 2})
  source: Int! @resolver(result: 7)
}

type Item {
  local: Int!
  total(extra: Int!): Int!
    @resolver(
      of: "local"
      qf: "remote: source"
      result: "sum(local, query:remote, $extra)"
    )
}
```

Query paths have the same integer, null, error, and list traversal behavior as object paths. Result expressions still require the referenced response keys to be present; the DSL has no conditional-result expression for absent fields. Variables providers run before inputs are materialized and cannot read `query:` paths.

## Result Shapes

Results are GraphQL literals accepted by Viaduct's JSON scalar: integers, strings, nulls, lists, and objects. They are interpreted against the resolver field's declared output type.

- `null` is allowed at nullable positions.
- `"ERROR"` produces `EngineErrorData` at any output position.
- Integer leaves may be integer literals or expression strings.
- Result objects may supply fields that have their own `@resolver`; those supplied values bypass the standard resolver.
- Concrete object results may not supply `__typename`.
- Non-Node interface and union results require `__typename` to select a concrete type.

Supported expressions are:

```text
value(v)
sum(v1, v2, ..., vn)
sumplus1(v1, v2, ..., vn)
```

Each value is `$argumentName`, a dot-separated path from the resolver's materialized `of` input, or `query:` followed by a path from its materialized `qf` input. `value(v)` requires exactly one reachable integer, null, or error and returns it unchanged. It therefore preserves nulls and errors instead of treating null as zero.

For the sum expressions, a path may cross lists; all reachable integer leaves contribute to the sum. Null objects, lists, and elements contribute zero. An error reached by any term makes the complete expression an error.

```graphql
extend type Query {
  collection: Collection!
    @resolver(
      result: {
        entries: [{value: 2}, null, {value: 3}]
      }
    )
}

type Collection {
  entries: [Entry]
  total(extra: Int!): Int!
    @resolver(
      of: "entries { value }"
      result: "sumplus1(entries.value, $extra)"
    )
}

type Entry {
  value: Int!
}
```

## JEXL Scripts

`script` supplies a JEXL program instead of a literal `result`. The fixture parses it once when constructing the resolver and interprets it for each invocation. Existing `result` expressions and Node resolver definitions retain their behavior, and both field resolver forms can appear in one world.

Each invocation receives fresh bindings in one `MapContext`:

- `objectValue` and `queryValue` expose the materialized `of` and `qf` inputs as read-only objects and lists. An absent, empty, or whitespace-only fragment produces a null binding. Dotted access and list indexing preserve response aliases, selected nulls, missing-field errors, and error-valued reads.
- Each field argument is bound by its schema name, including null for an absent optional argument. Script resolvers currently accept only integer arguments; `query`, `objectValue`, `queryValue`, and `error` are reserved binding names. Registry path/provider variables continue to bind RSS selections and do not become script globals.
- `query(selectionSet)` executes a braced Query-rooted selection set. An optional second map supplies integer variable bindings, for example `query('{ renamed: echo(value: $n) }', {'n': extra}).renamed`. Null bindings are also accepted. The result uses the same object/list views as RSS inputs.
- `error` is an opaque constant that produces `EngineErrorData` at any output position, including an entire object or list, an object field, or a list element. For example, `seed < 0 ? error : seed` returns either an explicit error or an integer. Errors remain outside the input-value domain and cannot be supplied as subquery variable values.

Scripts can return integers, ordinary objects, lists, and null. JEXL maps become partial Runtime2 objects, and lists or arrays become Runtime2 lists. Concrete output types can be inferred; abstract outputs require a string `__typename`. Both integer results and query variable bindings accept `Int`, `Long`, and `BigInteger` values within GraphQL Int's signed 32-bit range and normalize them to `Int`. Node/root-field references and other scalar result types continue to use `result`.

Output object keys are schema field names. An unaliased input or subquery object can be returned directly, retaining its concrete type. Returning an object containing aliased selections is rejected, including when an alias names another valid schema field. Construct an explicit output map instead: an input selected with `renamed: n` can become `{'n': objectValue.renamed}`. Automatic alias removal could merge selections with different arguments.

This direct-return alias validation uses the canonical field metadata retained by Runtime2's materialized objects. The public `EngineObjectData.Sync` contract requires reads by response key, including aliases, but does not expose the mapping from response keys to schema fields. Other compliant implementations can preserve aliases without exposing that metadata. The DSL's Runtime2 materializers supply it; a custom execution context returning another implementation must use explicit output maps for aliased objects because the adapter cannot validate their alias origins.

```graphql
extend type Query {
  item: Item! @resolver(script: "{'local': 2}")
  source: Int! @resolver(result: 7)
}

type Item {
  local: Int!
  total(extra: Int!): Int!
    @resolver(
      of: "local"
      qf: "remote: source"
      script: """
        var total = objectValue.local + queryValue.remote;
        return total + extra;
      """
    )
}
```

Scripts disable object construction, pragmas, annotations, and assignments to global bindings. Local variables remain mutable. Method and property permissions deny reflective class access while permitting the query callable and RSS reads.

Script syntax/runtime errors identify the resolver coordinate and a line within the JEXL source. Ordinary exceptions from the query callback retain JEXL's wrapper and their original cause, preserving the script call location. Cancellation and error-valued engine reads are unwrapped to preserve their engine semantics. Output-conversion errors identify the offending result path.

Evaluation runs on an elastic blocking dispatcher; `query` bridges to the existing resolution context with `runBlocking`, retaining the request's `Job` while removing its dispatcher so the engine dispatcher remains available for subqueries. In [FieldResolverTask.resolveSelectionSet](../../src/main/kotlin/viaduct/engine/runtime2/resolution/FieldResolverTask.kt), `forChildScope(fieldTaskScope)` creates a child operation whose spawned work remains owned by the invoking field task. Subquery setup starts on the script thread, including synchronous orchestration preparation and undispatched startup. Each call creates its own result root and orchestration state.

The shared state used by this entry path supports concurrent access: `ObjectCellStore` in [EngineResult.kt](../../src/main/kotlin/viaduct/engine/runtime2/model/EngineResult.kt) protects cell lookup, installation, and key snapshots with a monitor; [Promise](../../src/main/kotlin/viaduct/engine/runtime2/model/Promise.kt) uses atomic state and `CompletableDeferred`; [AbstractCycleCheckState](../../src/main/kotlin/viaduct/engine/runtime2/resolution/framework/AbstractCycleCheckState.kt) serializes writer registration and read-edge updates under its monitor. `VariableBindingsState` and `BindingDeclarationsState` use concurrent maps of promises.

The `publicationCycleTask` passed as the subquery's reader identifies the consuming parent field resolver by its result-root identity and publication path. It has no thread identity: materialization records the parent's dependencies on the nested result's writers even when the read runs on a script thread. Coroutine scope ownership and cycle-reader identity therefore survive the dispatcher change. Focused tests force overlapping real nested queries with both one and four engine threads.

## Node Results

Node IDs are globally unique within one DSL world. A node resolver's ordinary object result is the concrete body for that ID; its top-level `id` is injected by the fixture, while internal typename demand is resolved through the generated `V_A_typename` field resolver. Node resolvers may also return a structured root-field reference as described below. A complete top-down world places the root field before the Node type:

```graphql
extend type Query {
  viewer: User!
    @resolver(result: {id: "user-1"})
}

type User implements Node
  @nodeResolver(
    result: [
      {id: "user-1", result: {score: 7}}
    ]
  ) {
  id: ID!
  score: Int!
}
```

Any Node-typed field result is a reference containing only `id`. The referenced `NodeResult` provides its concrete type and body. A reference ID may be a literal or `idFrom($argumentName)`, which reads a non-null ID argument from the resolver field:

```graphql
extend type Query {
  viewer(id: ID!): User!
    @resolver(result: {id: "idFrom($id)"})
}

type User implements Node
  @nodeResolver(
    result: [
      {id: "user-1", result: {score: 7}},
      {id: "user-2", result: {score: 8}}
    ]
  ) {
  id: ID!
  score: Int!
}
```

Using an argument in `idFrom` reads it directly and does not define a resolver-registry variable.

An unknown ID, an ID registered for an incompatible Node type, a duplicate global ID, or additional fields in a Node reference is rejected.

## Root-Field Reference Results

Use a reserved JSON result object with `__ref` and optional `arguments` to return a canonical root-field reference. `__ref` is a dot-separated path starting with a field of Query; omit the `Query.` prefix. Intermediate fields must be singular, argumentless object fields, and the target must return a singular value. The existing reference factory validates the path and coerces arguments against the target schema. Extra keys, duplicate keys, invalid paths, and invalid arguments are rejected.

```graphql
extend type Query {
  viewer(id: ID): User
    @resolver(result: {__ref: "directory.lookup", arguments: {id: "$id"}})
  directory: Directory
}
type Directory {
  lookup(id: ID): User @resolver(result: {id: "idFrom($id)"})
}
type User implements Node
  @nodeResolver(result: [{id: "user-1", result: {score: 7}}]) {
  id: ID!
  score: Int
}
```

Reference argument values use GraphQL literals. A string consisting of `$name` reads an argument of the returning resolver directly; this works recursively in list and input-object arguments and does not declare a registry variable. Integer `value`, `sum`, and `sumplus1` expressions can also read the current resolver's object or Query inputs. Node-resolver results have no field arguments to substitute.

The reference form works for scalar or object results, fields supplied inside returned objects, list elements, and `@nodeResolver` results. It is recognized before ordinary object/Node-body interpretation, so a node resolver can return another reference without injecting an intermediate `id`. Reference targets can themselves return references. The engine follows the chain within one publication and retains the first Node reference's authoritative identity. This DSL support is broader than the animation's supported scope; list animations remain unsupported.

## Invocation Assertions

Resolver execution observations belong to semantics, not the model DSL. Use `viaduct.engine.runtime2.contract.ResolverApplicationArguments` as the operation's `resolutionObserver` (or pass it to `resolveAndValidate`) and assert its recorded argument tuples after resolution. Constructing or directly invoking a model fixture does not automatically record an application.

## Counterexample Construction

Use the DSL when a generated failure can be reduced to a small deterministic relationship among schema fields, resolver inputs, variables, outputs, and node references. Preserve the complete schema and triggering query, then encode only the resolver declarations needed to retain the failure. Assert the externally relevant result and add independent invocation, demand, binding, or lifecycle assertions for the property that was wrong.

Do not encode scheduler order into a DSL counterexample. Concurrency, cancellation races, observer delivery, GraphQL Java completion, executor adaptation, and bootstrap validation belong in focused Kotlin fixtures at their owning boundary. A DSL test should remain valid across resolver families that claim its feature contract unless the test intentionally targets production-only symbolic behavior.

When reducing a property-test failure, retain the reported seed and `S:Q` coordinate in the test description or commit context until the deterministic regression is established, but do not make the permanent test regenerate the original large world. The permanent fixture should explain the smallest semantic cause directly.
