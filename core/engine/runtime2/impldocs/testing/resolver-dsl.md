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
  result: JSON
) on FIELD_DEFINITION

input VariableDefinition {
  name: String!
  path: [String!]!
}
```

`result` must be present even when its value is `null`. The compiler reads the directives from the source AST, builds the ordinary resolver registry, and strips the directives before constructing the retained GraphQL schema. The model schema therefore does not acquire a JSON leaf type or DSL metadata.

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
- A name in the `providerVars` object is compiled as `FromProvider` and returned by the resolver's one variables-provider function.
- `pathVars` and `providerVars` names may not shadow field arguments or each other.
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

When reducing a property-test failure, retain the reported seed and `S:R:Q` coordinate in the test description or commit context until the deterministic regression is established, but do not make the permanent test regenerate the original large world. The permanent fixture should explain the smallest semantic cause directly.
