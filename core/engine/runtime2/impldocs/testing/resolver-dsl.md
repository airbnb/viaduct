# Resolver Test DSL

## Scope

The resolver-test DSL describes small deterministic resolver worlds for regression tests and counterexamples of failed resolution. A world embeds its schema, resolver dependencies, variable definitions, and resolver outputs in one GraphQL document. It does not replace tests of fixture composition, generated worlds, scheduling, tracing, or other internal execution mechanics.

Construct a world with `TestWorld.fromDSL(schemaSDL)`.

The [feature inventory](resolver-dsl-feature-inventory.md) maps remaining schema/registry declaration gaps to the native resolution suites and distinguishes script capabilities from fixtures that need Kotlin execution controls.

`ResolverTestDslDocument` parses and strips fixture metadata into plain declarations. `JEXLScript` provides the interpreter, bindings, read-only views, input validation, and standard checker outcomes without Runtime2 dependencies. Both live in Engine API test fixtures (`viaduct.engine.api.testing`). `ResolverTestDsl` and `JEXLNonselectiveFieldResolverFunction` adapt them to Runtime2 registry definitions, source-schema lowering, output values, references, execution contexts, and selective projection.

Engine API feature tests use `EngineTestModule.fromDSL(schemaSDL, scriptBindings = emptyMap())` with `import viaduct.engine.api.testing.executors.fromDSL`. This companion extension builds executors and required-selection sets for the existing module bootstrap and dispatcher harness. Both engines run the same declarations through `runContractTest`; `TestWorld` continues using its independent native registry adapter. Field and Node scripts default to selective output projection in both adapters; declare `isSelective: false` for Engine API Mutation resolvers and whenever nonselective behavior is the subject.

An optional trailing `MockTenantModuleDSL` block adds native executors against the same stripped schema. A field may combine a scripted resolver with a native checker or a native resolver with a scripted checker; Node resolvers and type checkers compose independently too. Declaring the same field resolver, Node resolver, field checker, or type checker in both forms is rejected. Use this block to retain batching, provider-owned RSS, demand inspection, policies, and lifecycle callbacks while moving ordinary declarations into the schema.

The scripting helpers and adapter are engine-only fixtures. The published tenant test-fixtures JAR excludes `viaduct.engine.api.testing`, JEXL, and its `commons-logging` dependency; the ordinary `EngineTestModule` has no scripting dependency.

The Engine API adapter obtains built-in types, root fields, directives, and scope metadata from its existing schema factory. The parser's minimal built-in schema remains the default for native Runtime2 fixture compilation.

The API adapter retains argument, object-path, and Query-path variable definitions and named checker pairs, including pair-local providers. Checker RSS entries use raw reads; ordinary resolver RSS reads retain checked failures and their diagnostics. Subquery results retain selection metadata through private fixture views so returning an aliased result as a schema-field output fails even when the alias matches another field name. RSS objects remain marked and cannot be returned.

Engine API output limits follow its executor contract: `error` fails the resolver invocation, object-valued `ref` uses native root-field references, and `ref('node', {'id': ...})` uses the type registered for that DSL Node fixture. Native Runtime2 additionally represents embedded errors and scalar references. Batching, provider-owned RSS, demand inspection/forwarding, custom policies, asynchronous objects, and lifecycle-sensitive executors remain Kotlin fixtures.

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
directive @nodeResolver(ids: [ID!]!, script: String!, isSelective: Boolean! = true) on OBJECT

directive @checker(inputs: [CheckerInput!]! = [], script: String!) on FIELD_DEFINITION | OBJECT

input CheckerInput {
  name: String!
  of: String
  qf: String
  variables: [VariableSource!]! = []
  vars: VariablesProvider
}

directive @resolver(
  isSelective: Boolean! = true
  of: String
  qf: String
  pathVars: [VariableDefinition!]! = []
  variables: [VariableSource!]! = []
  vars: VariablesProvider
  script: String!
) on FIELD_DEFINITION

input VariableDefinition {
  name: String!
  path: [String!]!
}

input VariableSource {
  name: String!
  fromArgument: [String!]
  fromObjectField: [String!]
  fromQueryField: [String!]
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

- A variable whose name matches an argument of the resolver field is inferred as `FromArgument` unless `variables` explicitly declares its source.
- A name in `variables` has exactly one source: `fromArgument`, `fromObjectField`, or `fromQueryField`. Argument paths begin with the field argument name and may traverse input objects; field paths use response keys from the corresponding `of` or `qf`. These compile into the engine's `FromArgument` or `FromField` declarations.
- A name in `pathVars` is compiled as `FromObjectField`.
- A name declared by `vars.names` is compiled as `FromProvider` and returned by the resolver's one variables-provider function.
- Provider names may not shadow field arguments or any explicit declarations. `pathVars` names may not shadow field arguments or `variables` declarations. Explicit `variables` names must be unique.
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

`variables` uses the same engine path validation as Kotlin fixture declarations: aliases, argument-bearing selections, and inclusion guards retain their original meaning. `fromQueryField` always reads `qf`, even when its variable is consumed by `of`; `fromObjectField` always reads `of`. A nested argument path binds null when an intermediate input object is null. Variable source paths cannot cross intermediate lists.

```graphql
extend type Query {
  result(input: Input!): Int! @resolver(
    of: "local: echo(value: $nested)"
    qf: "source remote: echo(value: $fromQuery)"
    variables: [
      {name: "nested", fromArgument: ["input", "value"]}
      {name: "fromQuery", fromQueryField: ["source"]}
    ]
    script: "objectValue.local + queryValue.remote"
  )
  source: Int! @resolver(script: "7")
  echo(value: Int!): Int! @resolver(script: "value")
}
input Input { value: Int! }
```

`pathVars.path` follows Runtime2's `FromObjectField` restrictions. In particular, an intermediate path component cannot cross a list. Scripts can traverse lists explicitly with indexing or loops.

`vars.names` must be a nonempty list of unique GraphQL names used in `of` or `qf`. The provider script runs once per active grounded resolver occurrence and returns one map with exactly those keys.

Provider scripts use the same JEXL configuration as field scripts. Each invocation receives fresh bindings for the field arguments by schema name, including null for absent optional arguments, and the reserved `error` constant. Provider scripts have no RSS inputs or execution callables. Argument objects and lists are recursively read-only. Provider arguments and results support null, integers, Boolean, String (including IDs and enum names), Double, lists, and input-object maps. Integer values are normalized to GraphQL Int's signed 32-bit range; the existing engine validates the resulting values against the fragment argument types.

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

## Checkers

`@checker` on a field declares a field checker, including fields supplied passively by an ancestor. On a concrete object type it declares a type checker; this includes `Query`. A field or type may declare one checker. A field's value resolver and its checker have independent input selections, variables, and scripts.

```graphql
extend type Query {
  item: Item @resolver(script: "{'owner': 7}")
  allowedOwner: Int @resolver(script: "7")
}

type Item @checker(
  inputs: [{name: "policy", of: "owner", qf: "allowedOwner"}]
  script: "inputs.policy.objectValue.owner == inputs.policy.queryValue.allowedOwner ? allow : deny"
) {
  owner: Int
  score(seed: Int): Int @resolver(script: "seed")
    @checker(script: "seed != null && seed >= 0 ? allow : deny")
}
```

Each `inputs` entry has a unique GraphQL `name` and optional `of`, `qf`, `variables`, and `vars`. The script receives a recursively read-only `inputs` map. Each pair exposes `objectValue` and `queryValue`, retaining aliases and separate response-key projections. Missing selections produce object values containing only the `"**FROM_RSS**"` metadata key, including both roots of an explicitly empty pair; they do not produce null. No input entries gives an empty `inputs` map. Use `inputs['pairName']` when a name collides with a JEXL map property, such as `empty`.

Field checker scripts also receive the checked field's grounded arguments by schema name. Type checker scripts have no field arguments. Checker bindings reserve `inputs`, `allow`, `deny`, `denyWithMessage`, and the existing resolver binding names. Checker scripts receive `query` and `mutation` execution bindings and have no `ref` callable. Native field and type checker contexts support Query execution and reject Mutation execution. Checker queries retain raw-read semantics; value resolvers activated within those queries enforce their own checked input boundaries.

Return the `allow` constant for `CheckerResult.Success`, or `deny` for an explicit `CheckerResult.Error`. Use `denyWithMessage(message)` to return the same denial policy with an `IllegalStateException` whose message is exactly the supplied String, for example `seed >= 0 ? allow : denyWithMessage('negative seed: ' + seed)`. The message may be empty; null and other value types are rejected with script coordinates and line numbers. Both denial forms prevent ordinary resolver reads and GraphQL output access; when combined with a field denial, the type denial is retained. Returning null, Boolean values, strings, or the resolver `error` marker is rejected. A script runtime failure throws and completes the checker result exceptionally. Syntax and runtime diagnostics identify `Type/field.checker` or `Type.checker` and the script line. Custom denial policies, exact error identities, applicability predicates, and callbacks with host effects remain Kotlin checker definitions passed through `TestWorld.fromDSL`'s existing callback maps. Declaring the same checker in both DSL and Kotlin is rejected.

Each pair uses the same explicit `variables` source vocabulary as field resolvers. Field checker pairs also infer whole-argument sources by matching variable names; explicit sources take priority. Type checker pairs reject `fromArgument`. Variables are shared between the two roots of one pair; equal names in different pairs remain independent. Unused, undefined, duplicate, and conflicting declarations are rejected. Aliases, nested argument paths, field paths, and inclusion guards retain their engine semantics.

```graphql
checked(seed: Int): Int @resolver(script: "seed") @checker(
  inputs: [
    {name: "argument", of: "echo(value: $n)", variables: [{name: "n", fromArgument: ["seed"]}]}
    {name: "computed", qf: "echo(value: $n)", vars: {names: ["n"], script: "{'n': seed * 2}"}}
  ]
  script: "inputs.argument.objectValue.echo * 2 == inputs.computed.queryValue.echo ? allow : deny"
)
```

`vars` runs once per pair for one active checker occurrence. Its script receives the field checker's arguments, or no arguments for a type checker, and returns exactly its declared names. Provider values must remain error-free input data: returning `error` or nesting it in a value fails the provider and its bindings, never assigns an error value to a variable. Provider diagnostics include the checker coordinate and pair name, for example `Item/checked.checker.inputs.computed.vars`.

The engine supplies checker inputs through its raw-read path. Reading a raw value does not enforce that value's field or type denial. A value resolver activated to produce a checker input still reads its own dependencies through ordinary checked semantics. The DSL uses the existing checker factories and materialized inputs, preserving this boundary; it does not resolve inputs itself.

## Query Fragments

`qf` is an optional selection-set string relative to `Query`. An absent or empty `qf` denotes an empty Query fragment. Like `of`, it preserves response-key aliases, nested selections, inclusion conditions, and resolver-owned variable bindings. `pathVars` still reads paths from the object fragment, even when the variable is used only by the Query fragment. Use `variables: [{name: "remote", fromQueryField: ["responseKey"]}]` to read a path from the Query fragment instead.

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

The fixture combines shared test bindings with invocation-local engine bindings in a `MapContext`:

- `objectValue` and `queryValue` expose the materialized `of` and `qf` inputs as read-only objects and lists. Every object map includes `"**FROM_RSS**": true`, including nested objects and objects inside lists. An absent, empty, or whitespace-only fragment produces a null binding. Dotted access and list indexing preserve response aliases, selected nulls, missing-field errors, and error-valued reads. Use explicit null tests when traversing nullable inputs: JEXL safe navigation (`?.`) and null coalescing (`??`) can suppress a failed input read.
- Each field argument is bound by its schema name, including null for an absent optional argument. Arguments support the same read-only JSON-like input values as provider scripts; `query`, `mutation`, `objectValue`, `queryValue`, `error`, and `ref` are reserved binding names. Registry variables bind RSS selections and do not become script globals. An explicit `variables` declaration overrides argument-name inference for the RSS only; a same-named script binding still holds the field argument.
- `query(selections, variables?)` executes Query-rooted selections. An optional map supplies error-free GraphQL input values, including null, integers, finite Float values, Booleans, strings (including IDs and enum names), lists, and input objects. For example, `query('{ renamed: user(id: $key) { score } }', {'key': 'user-1'}).renamed.score` retains the response alias. Variable names and input-object keys must be strings. The engine validates values against their selection argument types. The result uses read-only object/list views without the RSS marker.
- `mutation(selections, variables?)` executes ordered Mutation-rooted selections through the invoking context's mutation entry point, with the same input and result views. It requires a Mutation schema root and a context supporting mutations. It preserves repeated aliased occurrences and waits for all mutation tasks and payload work before exposing the result.
- `error` is an opaque constant that produces `EngineErrorData` at any output position, including an entire object or list, an object field, or a list element. For example, `seed < 0 ? error : seed` returns either an explicit error or an integer. Errors remain outside the input-value domain and cannot be supplied as subquery variable values.

Execution selections can be a braced selection set or a document containing only named fragment definitions. A multi-fragment document must name its entry point `Main`; a single fragment may have any name. Its type condition must match the selected Query or Mutation root. Named spreads expand through the existing schema parser, preserving response aliases and bound variables. Full `query`, `mutation`, and `subscription` operation documents are outside this interface; variables come from the explicit map.

```jexl
query(
  'fragment Main on Query { selected: user(id: $id) { ...Details } } fragment Details on User { score }',
  {'id': 'user-1'}
).selected.score
```

Scripts can return integers, IDs, strings, Booleans, Float values, enum names, ordinary objects, lists, null, and root-field references. JEXL maps become partial Runtime2 objects, and lists or arrays become Runtime2 lists. Concrete output types can be inferred; abstract outputs require a string `__typename`. Both integer results and execution variable bindings accept `Int`, `Long`, and `BigInteger` values within GraphQL Int's signed 32-bit range and normalize them to `Int`. ID results accept strings or integer values and normalize them to strings. Float results accept numbers and normalize them to finite `Double` values; numeric strings and nonfinite values are rejected. String and Boolean results require their corresponding JVM types, and enum results require a string naming a declared member. These checks also apply within lists, returned objects, and Node bodies. Custom scalar results remain outside the scripted output domain.

Output object keys are schema field names. Field and Node resolvers cannot return RSS objects, directly or nested inside fresh maps or lists. The presence of `"**FROM_RSS**"` rejects an output map regardless of its value, before output conversion, Node ID injection, or selective projection. The marker belongs only to script views; it does not modify engine objects. GraphQL input-object arguments and explicit query/mutation results are unmarked. Copy scalar values into fresh output maps: an input selected with `renamed: n` can become `{'n': objectValue.renamed}`.

An unaliased explicit query/mutation result object can be returned directly, retaining its concrete type. Returning an object containing aliased selections is rejected, including when an alias names another valid schema field. Construct an explicit output map instead. Automatic alias removal could merge selections with different arguments.

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

Scripts disable object construction, pragmas, annotations, and assignments to global bindings. Local variables remain mutable. Method and property permissions deny reflective class access while permitting execution callables and RSS reads. A callable sandbox exposes only `call` on the execution, reference, and denial-message helpers, blocking inherited methods and property access consistently across supported JEXL versions.

### Test-Supplied Bindings

`TestWorld.fromDSL(scriptBindings = mapOf(...))` supplies optional shared bindings to every field, Node, checker, and variables-provider script in that world. The default is an empty map. The map is supplied once, without a context factory; its values retain their identity and mutable state across invocations and executions. Engine inputs and field arguments remain invocation-local. Use different binding names for independent fixture state, and thread-safe state where callbacks can overlap.

```kotlin
val count = AtomicInteger()
val world = TestWorld.fromDSL(
    schemaSDL = """
        extend type Query {
          next: Int @resolver(script: "counter.incrementAndGet()")
        }
    """.trimIndent(),
    scriptBindings = mapOf("counter" to count),
)
```

Bindings can contain ordinary values, maps, lists, counters, or public helper objects. Methods and properties of directly bound objects are permitted for script access, including a public `call` method for function syntax such as `helper(value)`. Arbitrary classes returned by a helper do not acquire permissions automatically. `getClass()` and the `class` property remain blocked on directly bound objects; built-in callables retain their existing restrictions. Binding names must not collide with field arguments or the reserved names `query`, `mutation`, `objectValue`, `queryValue`, `error`, `ref`, `id`, `inputs`, `allow`, `deny`, and `denyWithMessage`.

A bound `EngineErrorData`, or one returned by a bound helper, can appear at any field or Node output position. Conversion preserves that error value and its original cause. These values remain invalid as provider results or execution variable inputs, including inside objects and lists.

This escape hatch supports fixture configuration, provider-entry counts, synchronous effects, and ordinary input snapshots. Helper calls retain normal JEXL exception wrapping, and results still pass through the existing resolver/provider/checker adapters. Native suspension, actual engine-context identity, custom denial policies, malformed engine carriers, and registry-construction tests remain Kotlin fixtures. Universal DSL migration is not a goal.

### Evaluation And Errors

Script syntax/runtime errors identify the resolver coordinate and a line within the JEXL source. Ordinary exceptions from execution callbacks retain JEXL's wrapper and their original cause, preserving the script call location. Cancellation and error-valued engine reads are unwrapped to preserve their engine semantics, including deferred reads when validating or converting a returned subquery object. Output-conversion errors identify the offending result path.

Resolvers, checkers, and variables providers can deliberately fail with JEXL's `throw` statement, for example `throw 'permission denied';`. An uncaught throw becomes a `RuntimeException` at the shared evaluation boundary, capturing its JVM call stack there. Its message includes the script name, line, column, and thrown value, for example `Query/checked.checker@1:1: permission denied`; the original `JexlException.Throw` remains its cause. A script can catch its own throws with ordinary JEXL `try`/`catch`. A thrown failure aborts the invocation; it does not return `error`, a checker denial, or an error-valued variable.

Evaluation runs on an elastic blocking dispatcher; execution callables bridge to the invoking resolution context with `runBlocking`, retaining the request's `Job` while removing its dispatcher so the engine dispatcher remains available for nested work. Field, node, field-checker, and type-checker scripts use the same bridge, preserving cancellation, task creators, and context-specific capabilities. Providers have no execution bindings. In [FieldResolverTask.resolveSelectionSet](../../src/main/kotlin/viaduct/engine/runtime2/resolution/FieldResolverTask.kt), `forChildScope(fieldTaskScope)` creates a child operation whose spawned work remains owned by the invoking field task. Subquery setup starts on the script thread, including synchronous orchestration preparation and undispatched startup. Each call creates its own result root and orchestration state.

The shared state used by this entry path supports concurrent access: `ObjectCellStore` in [EngineResult.kt](../../src/main/kotlin/viaduct/engine/runtime2/model/EngineResult.kt) protects cell lookup, installation, and key snapshots with a monitor; [Promise](../../src/main/kotlin/viaduct/engine/runtime2/model/Promise.kt) uses atomic state and `CompletableDeferred`; [AbstractCycleCheckState](../../src/main/kotlin/viaduct/engine/runtime2/resolution/framework/AbstractCycleCheckState.kt) serializes writer registration and read-edge updates under its monitor. `VariableBindingsState` and `BindingDeclarationsState` use concurrent maps of promises.

The `publicationCycleTask` passed as the subquery's reader identifies the consuming parent field resolver by its result-root identity and publication path. It has no thread identity: materialization records the parent's dependencies on the nested result's writers even when the read runs on a script thread. Coroutine scope ownership and cycle-reader identity therefore survive the dispatcher change. Focused tests force overlapping real nested queries with both one and four engine threads.

`ref(path)` or `ref(path, arguments)` constructs a root-field reference, including at nested object fields and list elements. The path starts at Query, for example `ref('namespace.number', {'value': seed})`; arguments are ordinary error-free input values. The existing reference factory validates the connected namespace path, singular target, and schema argument types. A reference is an instruction for the engine to follow, not a subquery executed while the script runs. Node-typed output maps contain an `id`; abstract Node outputs also specify `__typename`, for example `{'__typename': 'User', 'id': userId}`. The source-schema adapter lowers these maps to the existing Node reference representation.

## Selective Output

`@resolver` and `@nodeResolver` accept `isSelective: Boolean! = true`, using the production directive's argument name. The fixture default is deliberately true so ordinary tests exercise selective resolution; the production directive defaults to false. A true value registers a selective function that runs the ordinary script, converts its result, and projects it with `snipToDemand`. Scripts construct candidate values without a demand binding. Projection retains canonical demanded fields recursively through concrete objects and lists, including fields required by downstream resolvers and checkers. Nulls, errors, simple values, and symbolic references retain their existing meaning; reference targets receive their own demand.

```graphql
extend type Query {
  item: Item @resolver(isSelective: true, script: "{'value': 7, 'extra': 9}")
}
type Item { value: Int extra: Int }
```

For `{ item { value } }`, the resolver supplies only `value`. Explicit `isSelective: false` retains the complete candidate output at the fixture resolver boundary; the resolution strategy still applies its own output policy. Invocation counts and supplied-demand assertions remain Kotlin observations of engine execution. Shared contracts that also run against the complete-output reference tiers set `isSelective` from their subject's `selectiveResolvers` policy explicitly.

Selective Node registration also permits the engine to skip the lookup when a concrete Node reference needs only its authoritative ID, or has no Node-owned demand. Nonselective Node lookups still execute for that demand. When a selective lookup runs, projection uses the Node-owned demand and the engine restores the authoritative ID afterward. This distinction does not change the fixture's separate routing of an untyped `Query.node(id:)` request.

## Scripted Node Resolvers

`@nodeResolver(ids: [ID!]!, script: String!, isSelective: Boolean! = true)` declares the IDs routed to one concrete Node type and a JEXL lookup function for those IDs. IDs are globally unique within a DSL world. The script receives `id`, `ref`, `error`, `query`, and `mutation`; it has no object/Query fragment inputs. Execution bindings delegate to the lookup's supplied context, retaining its native capabilities and task ownership. An object result supplies the Node body, with its authoritative ID added by the adapter. Scripts cannot override that ID. They can also return `ref(...)`, null, or `error`. Unregistered IDs return null when a lookup executes; selective ID-only references can skip the lookup as described above.

The Engine API adapter requires an object result, following `NodeResolverExecutor`'s non-null result contract, and reports an undeclared ID as a failed lookup with the Node coordinate and ID. Its `ref('node', ...)` helper rejects undeclared IDs before execution; native resolver composition can still construct such a reference. Both adapters retain their behavior for selective and nonselective lookups. Request a non-ID field when testing these outcomes so the lookup executes.

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
