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
directive @nodeResolver(ids: [ID!]!, script: String!) on OBJECT

directive @resolver(
  of: String
  qf: String
  pathVars: [VariableDefinition!]! = []
  vars: VariablesProvider
  script: String!
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

`script` is required and must be a string. The compiler reads the directives from the source AST, builds the ordinary resolver registry, and strips the directives before constructing the retained GraphQL schema. DSL metadata does not add types to the model schema.

## Field Resolvers

`of` is an optional selection-set string relative to the field's parent object type. An absent or empty `of` denotes an empty object fragment. Response-key aliases are preserved in the resolver-visible input. Co-applicable selections sharing one response key must identify a compatible field invocation.

Selection sets use ordinary GraphQL validation. Invalid arguments and directive conditions are rejected when the world is constructed. String values such as `"ERROR"` have no special meaning.

Every variable used by `of` or `qf` is defined exactly once, including names shared by both fragments:

- A variable whose name matches an argument of the resolver field is inferred as `FromArgument`.
- A name in `pathVars` is compiled as `FromObjectField`.
- A name declared by `vars.names` is compiled as `FromProvider` and returned by the resolver's one variables-provider function.
- Provider names and `pathVars` names may not shadow field arguments or each other.
- Unused, missing, and duplicate variable definitions are rejected.

```graphql
extend type Query {
  result(seed: Int!): Int!
    @resolver(
      of: "source consume(value: $fromSource)"
      pathVars: [{name: "fromSource", path: ["source"]}]
      script: "(objectValue.consume == null ? 0 : objectValue.consume) + (seed == null ? 0 : seed)"
    )

  source: Int! @resolver(script: "7")

  consume(value: Int!): Int!
    @resolver(script: "1 + (value == null ? 0 : value)")
}
```

Using `seed` in a script reads the field argument directly and does not define a registry variable. Only `$seed` inside `of` or `qf` creates an inferred `FromArgument` definition.

`pathVars.path` follows Runtime2's `FromObjectField` restrictions. In particular, an intermediate path component cannot cross a list. Scripts can traverse lists explicitly with indexing or loops.

`vars.names` must be a nonempty list of unique GraphQL names used in `of` or `qf`. The provider script runs once per active grounded resolver occurrence and returns one map with exactly those keys.

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

Read the materialized Query input through `queryValue` and the object input through `objectValue`.

```graphql
extend type Query {
  item: Item! @resolver(script: "{'local': 2}")
  source: Int! @resolver(script: "7")
}

type Item {
  local: Int!
  total(extra: Int!): Int!
    @resolver(
      of: "local"
      qf: "remote: source"
      script: "(objectValue.local == null ? 0 : objectValue.local) + (queryValue.remote == null ? 0 : queryValue.remote) + (extra == null ? 0 : extra)"
    )
}
```

## JEXL Scripts

`script` supplies a JEXL program. The fixture parses it once when constructing the resolver and interprets it for each invocation.

Each invocation receives fresh bindings in one `MapContext`:

- `objectValue` and `queryValue` expose the materialized `of` and `qf` inputs as read-only objects and lists. An absent, empty, or whitespace-only fragment produces a null binding. Dotted access and list indexing preserve response aliases, selected nulls, missing-field errors, and error-valued reads. Use explicit null tests when traversing nullable inputs: JEXL safe navigation (`?.`) and null coalescing (`??`) can suppress a failed input read.
- Each field argument is bound by its schema name, including null for an absent optional argument. Arguments support the same read-only JSON-like input values as provider scripts; `query`, `objectValue`, `queryValue`, `error`, and `ref` are reserved binding names. Registry path/provider variables continue to bind RSS selections and do not become script globals.
- `query(selectionSet)` executes a braced Query-rooted selection set. An optional second map supplies integer variable bindings, for example `query('{ renamed: echo(value: $n) }', {'n': extra}).renamed`. Null bindings are also accepted. The result uses the same object/list views as RSS inputs.
- `error` is an opaque constant that produces `EngineErrorData` at any output position, including an entire object or list, an object field, or a list element. For example, `seed < 0 ? error : seed` returns either an explicit error or an integer. Errors remain outside the input-value domain and cannot be supplied as subquery variable values.

Scripts can return integers, IDs, ordinary objects, lists, null, and root-field references. JEXL maps become partial Runtime2 objects, and lists or arrays become Runtime2 lists. Concrete output types can be inferred; abstract outputs require a string `__typename`. Both integer results and query variable bindings accept `Int`, `Long`, and `BigInteger` values within GraphQL Int's signed 32-bit range and normalize them to `Int`. ID results accept strings or integer values and normalize them to strings. Other scalar result types remain outside the scripted output domain.

Output object keys are schema field names. An unaliased input or subquery object can be returned directly, retaining its concrete type. Returning an object containing aliased selections is rejected, including when an alias names another valid schema field. Construct an explicit output map instead: an input selected with `renamed: n` can become `{'n': objectValue.renamed}`. Automatic alias removal could merge selections with different arguments.

This direct-return alias validation uses the canonical field metadata retained by Runtime2's materialized objects. The public `EngineObjectData.Sync` contract requires reads by response key, including aliases, but does not expose the mapping from response keys to schema fields. Other compliant implementations can preserve aliases without exposing that metadata. The DSL's Runtime2 materializers supply it; a custom execution context returning another implementation must use explicit output maps for aliased objects because the adapter cannot validate their alias origins.

```graphql
extend type Query {
  item: Item! @resolver(script: "{'local': 2}")
  source: Int! @resolver(script: "7")
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

`ref(path)` or `ref(path, arguments)` constructs a root-field reference, including at nested object fields and list elements. The path starts at Query, for example `ref('namespace.number', {'value': seed})`; arguments are ordinary error-free input values. The existing reference factory validates the connected namespace path, singular target, and schema argument types. A reference is an instruction for the engine to follow, not a subquery executed while the script runs. Node-typed output maps contain an `id`; abstract Node outputs also specify `__typename`, for example `{'__typename': 'User', 'id': userId}`. The source-schema adapter lowers these maps to the existing Node reference representation.

## Scripted Node Resolvers

`@nodeResolver(ids: [ID!]!, script: String!)` declares the IDs routed to one concrete Node type and a JEXL lookup function for those IDs. IDs are globally unique within a DSL world. The script receives `id`, `ref`, and `error`; it has no object/Query fragment inputs or subquery callable. An object result supplies the Node body, with its authoritative ID added by the adapter. Scripts cannot override that ID. They can also return `ref(...)`, null, or `error`. Unknown IDs return null.

```graphql
extend type Query {
  viewer(id: ID!): User @resolver(script: "{'id': id}")
  redirect: User @resolver(script: "{'id': 'canonical'}")
}

type User implements Node
  @nodeResolver(
    ids: ["alias", "canonical"]
    script: "id == 'alias' ? ref('redirect') : {'score': 7}"
  ) {
  id: ID!
  score: Int
}
```

The first Node identity remains authoritative when a lookup returns a reference to another Node.

## Root-Field References

A `ref` path begins with a field of Query; omit the `Query.` prefix. Intermediate fields must be singular, argumentless namespace fields, and the target must return a singular value. Namespace resolvers are not invoked to traverse the path. Reference arguments are JEXL maps of ordinary input values, so scripts can use their arguments and materialized inputs directly.

References work for scalar or object outputs, fields supplied inside returned objects, list elements, and Node resolver results. Reference targets can themselves return references. The engine follows the chain within one publication and retains the first Node reference's authoritative identity. This scripting support is broader than the animation's supported scope; list animations remain unsupported.

## Invocation Assertions

Resolver execution observations belong to semantics, not the model DSL. Use `viaduct.engine.runtime2.contract.ResolverApplicationArguments` as the operation's `resolutionObserver` (or pass it to `resolveAndValidate`) and assert its recorded argument tuples after resolution. Constructing or directly invoking a model fixture does not automatically record an application.

## Counterexample Construction

Use the DSL when a generated failure can be reduced to a small deterministic relationship among schema fields, resolver inputs, variables, outputs, and node references. Preserve the complete schema and triggering query, then encode only the resolver declarations needed to retain the failure. Assert the externally relevant result and add independent invocation, demand, binding, or lifecycle assertions for the property that was wrong.

Do not encode scheduler order into a DSL counterexample. Concurrency, cancellation races, observer delivery, GraphQL Java completion, executor adaptation, and bootstrap validation belong in focused Kotlin fixtures at their owning boundary. A DSL test should remain valid across resolver families that claim its feature contract unless the test intentionally targets production-only symbolic behavior.

When reducing a property-test failure, retain the reported seed and `S:Q` coordinate in the test description or commit context until the deterministic regression is established, but do not make the permanent test regenerate the original large world. The permanent fixture should explain the smallest semantic cause directly.
