# Selection Execution via ExecutionHandle

Resolvers sometimes need to ask follow-up questions of the graph.

A tenant resolver might load an object, then choose a named query operation to fetch more data from the root schema using values it just computed. Starting a new GraphQL-Java execution for each of these "subqueries" would throw away the state the engine already has for the current request.

The selection execution path (`ctx.query()` / `ctx.mutation()`) is the engine's way of doing this without rebuilding everything. It reuses the existing request context through an opaque `ExecutionHandle`, but runs the selection with an isolated root/query result boundary so resolver-driven subqueries do not accidentally share root memoization with the parent query or sibling subqueries.

This document follows a selection execution from the resolver's `ctx.query()` call all the way through execution and back.

## Terminology

- **Selection Execution**: An internal engine call issued from a resolver that runs against the same request and execution state, rather than starting a new GraphQL-Java execution. Sometimes called "subquery" in informal discussion.
- **Tenant vs Engine**: "Tenant" refers to the generated resolver layer and its API types (`Context`, `QueryFromAnnotation`, `MutationFromAnnotation`). "Engine" refers to the shared execution core (planning, field resolution, access checks) that powers all tenants. The tenant runtime uses `SelectionSet<T>` internally to bridge annotated operations to engine selections.
- **ExecutionHandle**: An opaque reference to the parent request's `ExecutionParameters`. Used to recover engine state when running selection executions.
- **EngineSelectionSet**: The engine's untyped representation of selections, variables, and fragments—what the planner actually consumes.
- **GRT objects**: Generated, strongly-typed GraphQL Representational Types (e.g., `Query`, `Mutation`) returned to tenant resolvers.
- **EEC**: `EngineExecutionContext`, the request-scoped context that provides access to schema, execution state, and the `resolveSelectionSet()` API.

## Three-Tier Architecture

Selection execution uses a three-tier API architecture:

| Tier | API | Purpose | Consumers |
|------|-----|---------|-----------|
| **Kotlin Tenant** | `ctx.query(QueryFromAnnotation, variables)` / `ctx.mutation(MutationFromAnnotation, variables)` | Annotated operations, build-time validation | Resolver code |
| **Engine API** | `EEC.resolveSelectionSet(selectionSet, options)` | Flexible, configurable, triggers resolution | Advanced tenant runtime integrations, engine internals |
| **Engine API** | `EEC.completeSelectionSet(selectionSet, arguments, options)` | Complete already-resolved fields | Classic-on-Modern shims path |
| **Wiring** | `Engine.resolveSelectionSet(handle, selectionSet, options)` | Implementation detail | Only called by EEC |
| **Wiring** | `Engine.completeSelectionSet(handle, selectionSet, ...)` | Implementation detail | Only called by EEC |

This layering provides:

- Simple APIs for common cases (tenant layer)
- Flexibility for advanced use cases (via `ResolveSelectionSetOptions`)
- Clear separation of concerns (EEC handles validation and delegates to Engine)

## When to Use Subqueries

Typical use cases for `ctx.query()` / `ctx.mutation()`:

- Choosing among named operations based on runtime data (e.g., only execute an operation selecting expensive fields if a previous check passes)
- Fetching fields from related types that aren't part of the current resolver's return type (e.g., loading user details when resolving a reservation)
- Reusing existing schema logic instead of reimplementing it in tenant code

For dependencies that can be fetched before the resolver runs, prefer `@Resolver`'s `objectValueFragment` or `queryValueFragment` (represented as required selection sets in the engine). Subqueries still have fixed operation documents; runtime logic chooses which operation to execute and supplies its variables.

## Overview

```
┌─────────────────────────────────────┐
│ Step 1: Tenant Resolver             │
│ ctx.query(operation, variables)     │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Step 2: Tenant Runtime Bridge       │
│ ResolverExecutionContextImpl        │
│ → EngineExecutionContextWrapperImpl │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Step 3: Engine API Layer            │
│ EEC.resolveSelectionSet()              │
│ (validates handle)       │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Step 4: Wiring Layer                │
│ Engine.resolveSelectionSet()           │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Step 5: Build Child Parameters      │
│ QueryPlanFactory.buildFromSelections()│
│ ExecutionParameters.forChildPlan()  │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Step 6: Field Resolution            │
│ fieldResolver.fetchObject()         │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Step 7: Result Conversion           │
│ toObjectGRT() → typed GRT object    │
└─────────────────────────────────────┘
```

## Step 1: The Resolver Calls ctx.query()

From a Kotlin resolver, subquery execution starts with `ctx.query(operation, variables)` or `ctx.mutation(operation, variables)`. The operation is a singleton `object` annotated with `@GraphQLOperation` that extends `QueryFromAnnotation` or `MutationFromAnnotation`, respectively. Its document is validated against the tenant module's compilation schema at build time.

Runtime-dependent fields are handled by choosing among predefined operation objects, not by constructing GraphQL strings. Variable values come from the map passed to `query()` or `mutation()`, not from the parent request's variables. See the [Kotlin operation examples](../docs/docs/docs/developers/resolvers/graphql_operations.md#runtime-branching).

The public Kotlin execution methods accept neither strings nor `SelectionSet<T>`. `ctx.selectionsFor(...)` remains available for APIs that consume selection sets, but its result cannot be passed to `ctx.query()` or `ctx.mutation()`.

`ctx.mutation()` works the same way but is only available in mutation resolvers — the generated tenant API doesn't expose `mutation()` on query resolver contexts, so attempting to call it is a compile-time error.

Nested subqueries are supported and run within the same parent execution handle. Mutation resolvers can call query subqueries freely. Note that while the tenant API prevents query resolvers from calling mutation subqueries (compile-time), the engine layer itself does not enforce this restriction — code that bypasses the tenant API could still do so.

## Step 2: The Tenant Runtime Bridge

The `Context` type that resolvers see is generated from the resolver base class. At runtime, these are implementations that extend `ResolverExecutionContextImpl`.

When a resolver calls `ctx.query(operation, variables)`, the call flows through the bridge:

1. `ResolverExecutionContextImpl.query()` determines the root Query type and passes it, `operation.operationText`, and the variables to `EngineExecutionContextWrapperImpl.selectionsForOperation()`.
2. `selectionsForOperation()` normalizes the operation into a fragment document, inlines reachable fragments from the tenant module's `knownFragments`, and normalizes input variable values for the engine. The engine selection-set factory creates an `EngineSelectionSet`, wrapped in a tenant `SelectionSetImpl`.
3. A private `query(SelectionSet<T>)` helper delegates to the wrapper's `query()`, which unwraps the `EngineSelectionSet` and calls `EngineExecutionContext.resolveSelectionSet(engineSelectionSet, ResolveSelectionSetOptions.DEFAULT)`.
4. The wrapper converts the returned `EngineObjectData` to a typed Query GRT using `toObjectGRT()`.

`MutationFieldExecutionContextImpl.mutation()` follows the same path with the root Mutation type, a `MutationFromAnnotation` object, and `ResolveSelectionSetOptions.MUTATION`.

The subquery bridge converts:

- **To engine**: annotated operation + variables → internal `SelectionSet<T>` wrapping an `EngineSelectionSet` (the `ExecutionHandle` is accessed internally)
- **Back to tenant**: `EngineObjectData` → typed GRT objects (via `toObjectGRT()`)

The lower-level engine selection APIs are unchanged. Their use of `EngineSelectionSet` does not expose string-based execution on Kotlin resolver contexts.

**Key files:**

- `core/tenant/runtime/.../context/ResolverExecutionContextImpl.kt` — tenant-facing `query()` method
- `core/tenant/runtime/.../context/MutationFieldExecutionContextImpl.kt` — tenant-facing `mutation()` method
- `core/tenant/runtime/.../context/EngineExecutionContextWrapper.kt` — operation normalization and bridge implementation

## Step 3: The Engine API Layer

The Engine API layer is `EngineExecutionContextImpl.resolveSelectionSet()`. This method:

1. Validates that `executionHandle` is available — fails fast with `SubqueryExecutionException` if not
2. Delegates to `Engine.resolveSelectionSet()` with the handle, selection set, and options

The tenant wrapper calls `resolveSelectionSet()` with `ResolveSelectionSetOptions.DEFAULT` for queries or `ResolveSelectionSetOptions.MUTATION` for mutations.

### ResolveSelectionSetOptions

`ResolveSelectionSetOptions` provides flexibility for advanced use cases. See `engine/api/.../ResolveSelectionSetOptions.kt` for the full definition.

Options:

- `operationType` — Query or Mutation (default: Query)
- `targetResult` — Memoization control (default: fresh instance)

All options require a valid `executionHandle`. If the handle is not available (e.g., execution hasn't started yet), `resolveSelectionSet()` throws immediately rather than silently degrading.

**Key files:**

- `engine/api/.../ResolveSelectionSetOptions.kt` — options definition
- `engine/runtime/.../EngineExecutionContextImpl.kt` — `resolveSelectionSet()` implementation

## Step 4: The Wiring Layer

The wiring layer is `EngineImpl.resolveSelectionSet()`. It takes the opaque `ExecutionHandle`, an `EngineSelectionSet`, and a `ResolveSelectionSetOptions` instance that carries the operation type and optional target `ObjectEngineResult`.

This method:

1. Recovers the parent `ExecutionParameters` from the handle via `asExecutionParameters()`
2. Looks up the root type (`queryType` or `mutationType`) from `fullSchema`
3. Builds a `QueryPlan` from the provided `EngineSelectionSet`
4. Calls `parentParams.forChildPlan(..., ChildQueryPlanTarget.IsolatedRootResults(...))` to build child execution parameters
5. Runs the field-resolution pipeline and wraps the result

The `ExecutionHandle` is deliberately opaque -- tenant code sees `EngineExecutionContext.ExecutionHandle`, not `ExecutionParameters`. A handle is tied to the engine instance and request that created it; it cannot be reused across requests or engine instances.

Inside the runtime module, `asExecutionParameters()` bridges that gap. If someone fabricates a handle that isn't an `ExecutionParameters`, the cast fails with `SubqueryExecutionException.invalidExecutionHandle()`.

**Key files:**

- `engine/api/.../Engine.kt` — `resolveSelectionSet()` interface
- `engine/wiring/.../EngineImpl.kt` — implementation
- `engine/runtime/.../execution/ExecutionHandleExtensions.kt` — handle extraction

## Step 5: Building Child Execution Parameters

The core of subquery execution is the `QueryPlanFactory.buildFromSelections()` plus `ExecutionParameters.forChildPlan()` handoff. The wiring layer builds `QueryPlan.Parameters` using `fullSchema`, creates the plan from the provided `EngineSelectionSet`, then asks the parent execution parameters to derive execution state for the child plan.

### Schema Choice

Subqueries always use `fullSchema`, not `activeSchema`. The active schema can be a restricted view (for introspection or scoped concerns), but subqueries are internal server-side calls. When a resolver issues a subquery, it's asking the engine to consult the full graph, not mimic a client's restricted view.

### Variable Scoping

Subqueries do not inherit variables from the parent request. Variables come only from the subquery's own `EngineSelectionSet`, built by the tenant runtime from the annotated operation and the explicit variables map.

This means:

- Two executions of the same operation object with different `variables` maps remain independent
- Subquery variables don't leak back to the parent
- Changes to parent request variables cannot affect subquery behavior

### Memoization Control

The `targetResult` option controls memoization. `ObjectEngineResultImpl` holds resolved field results. By choosing which instance to pass:

- Fresh `ObjectEngineResultImpl` → fresh root result for this selection execution
- Existing `ObjectEngineResultImpl` → reuse that root result for this selection execution

Selection executions also get an isolated root/query result boundary via `ChildQueryPlanTarget.IsolatedRootResults`. This is separate from the root `targetResult` itself:

- `ctx.query()` uses the target Query result as both the root result and query result.
- `ctx.mutation()` uses the target Mutation result as the root result and creates a fresh Query result for any `querySelections` or Query-typed child plans launched inside the sub-mutation.

This matters for parallel subqueries. Without an isolated query result, two parallel `ctx.mutation()` calls can share the parent request's Query-root memoization, so a `querySelections` child plan such as `Query.node(id:)` can accidentally reuse a lazy node source resolved for a sibling sub-mutation. The lower-level `EEC.resolveSelectionSet()` with custom `targetResult` can still reuse a root result for advanced use cases, but it does not reuse the parent request's root/query results implicitly.

`completeSelectionSet()` has different semantics: when it receives an explicit `targetResult`, it uses `ChildQueryPlanTarget.ExplicitObjectResult`, which changes only the current object result and preserves the surrounding root/query execution constants.

Normal child plans use semantic targets derived from their parent type: `CurrentObjectResult` executes against the current object result, while `CurrentQueryResult` executes against the active Query result. Specialized paths use `ResolvedFieldObjectResult`, `ExplicitObjectResult`, or `IsolatedRootResults`; there is no context-dependent default target.

### Building the QueryPlan

At the engine planning boundary, subqueries arrive as an `EngineSelectionSet` that already contains the parent type, selection AST, fragment definitions, and variables. The tenant runtime has already converted the annotated operation document into this representation. `QueryPlanFactory.buildFromSelections()` feeds it directly into the plan builder without re-parsing the original operation document.

Plan caching keys on selection text, document key, and schema hash. Variables are not part of the cache key—the plan only depends on field/argument structure, not specific values.

**Key files:**

- `engine/runtime/.../execution/ExecutionParameters.kt` - child-plan parameter derivation and semantic targets
- `engine/runtime/.../execution/QueryPlanFactory.kt` — `buildFromSelections()`

## Step 6: Field Resolution

Once `forChildPlan()` produces child `ExecutionParameters`, the wiring layer runs the standard field-resolution pipeline:

- `fieldResolver.fetchObject()` for queries
- `fieldResolver.fetchObject(serialDispatch = true)` for mutations

Selections execute from the selected operation root (`Query` or `Mutation`), not as nested fields under the resolver that issued them. Query-typed child plans get the execution root, a fresh root `ExecutionStepInfo`, and the isolated query result chosen for this selection execution. Mutation-typed root plans use the isolated mutation result as their parent result and execute serially.

Results are stored in the selected root result, and a `ProxyEngineObjectData` wraps that result.

## Step 7: Result Conversion

Back in `EngineExecutionContextWrapperImpl`, the `EngineObjectData` result is converted to a typed GRT object via `toObjectGRT()`. The resolver receives a strongly-typed `Query` or `Mutation` object with accessor methods for the selected fields.

## Error Handling

Selection execution wraps failures in `SubqueryExecutionException`:

- **Missing handle**: `resolveSelectionSet()` throws immediately if `executionHandle` is null
- **Invalid handle**: `asExecutionParameters()` throws `invalidExecutionHandle()` if the handle isn't an `ExecutionParameters`
- **Selection type mismatch**: If the `EngineSelectionSet.type` doesn't match the root type for the operation (e.g., passing a `User` selection to a Query subquery)
- **Plan build issues**: Wrapped in `queryPlanBuildFailed(e)`
- **Field resolution failures**: Wrapped in `fieldResolutionFailed(e)`

Note that an empty `EngineSelectionSet` causes `IllegalArgumentException` (not `SubqueryExecutionException`) since it represents a programmer error rather than a runtime failure.

Each `ExecutionParameters` has its own `ErrorAccumulator`, so selection errors flow back into `EngineResult.errors` with correct attribution. From the tenant side, failures surface as errors on the returned GRT object's result, just like top-level execution errors.

**Key file:** `engine/api/.../SubqueryExecutionException.kt`

## Comparison with Other Patterns

| Pattern | Use Case | Mechanism |
|---------|----------|-----------|
| `@Resolver(objectValueFragment = ..., queryValueFragment = ...)` | Declarative parent/root dependencies | Registered as child plans, available before the resolver runs |
| `ctx.query(operation, variables)` / `ctx.mutation(operation, variables)` | Imperative execution, including runtime choice among named operations | Converts annotated operation to engine selections and executes via ExecutionHandle |
| `EEC.resolveSelectionSet(selectionSet, options)` | Advanced runtime integrations | Configurable execution options |
| `EEC.completeSelectionSet(...)` | Completing already-resolved fields | No field resolution, just completion |

Use `@Resolver` fragments for dependencies the engine can fetch and batch before the resolver runs. Use annotated operations with `ctx.query()` / `ctx.mutation()` when runtime logic determines execution, variable values, or which named operation to use. Use `EEC.resolveSelectionSet()` with custom options for advanced tenant runtime integrations, not as a replacement for the removed Kotlin string overloads.

## resolveSelectionSet vs completeSelectionSet

The engine provides two methods for selection set execution with different purposes:

| Aspect | `resolveSelectionSet` | `completeSelectionSet` |
|--------|----------------------|------------------------|
| **Purpose** | Trigger field resolution for new data | Complete already-resolved fields into ExecutionResult |
| **When to use** | New subquery from resolver | Shims completing RSS data from Classic-on-Modern path |
| **Field resolution** | Triggers via FieldResolver | Waits for existing resolution in the OER |
| **Returns** | `EngineObjectData` (OER wrapper) | `ExecutionResult` (completed Map + errors) |
| **Variables** | Passed via RawSelectionSet | Resolved internally from RSS + arguments |

### When to Use Each API

**Use `resolveSelectionSet` when:**

- You need to fetch new data that hasn't been resolved yet
- You're implementing a resolver that needs to issue subqueries
- You want the full field resolution pipeline (access checks, data loaders, etc.)

```kotlin
// Use resolveSelectionSet when you need to trigger new resolution
val data = eec.resolveSelectionSet(selectionSet, options)
```

**Use `completeSelectionSet` when:**

- Fields have already been resolved and stored in an ObjectEngineResult
- You need to complete the data into a final ExecutionResult with proper error handling
- You're working in the Classic-on-Modern shims path where RSS data has been resolved elsewhere

```kotlin
// Use completeSelectionSet when fields are already resolved (e.g., via RSS)
val result = eec.completeSelectionSet(selectionSet, arguments, options)
```

### completeSelectionSet Internals

The `completeSelectionSet` method performs these steps:

1. **Determine target OER**: Uses the provided `targetResult` or falls back to the parent from the execution handle
2. **Resolve RSS variables**: Calls `FieldExecutionHelpers.resolveRSSVariables()` using the provided arguments and engine data
3. **Build QueryPlan**: Converts the RequiredSelectionSet to a RawSelectionSet and builds a QueryPlan
4. **Create child parameters**: Builds child ExecutionParameters for the completion
5. **Complete and build result**: Calls `FieldCompleter.completeObject()` and builds the final ExecutionResult

### CompleteSelectionSetOptions

The `CompleteSelectionSetOptions` class provides configuration:

- `bypassAccessChecks` — Skip access checks during completion (used when completing fields for checker execution)
- `isFieldTypePlan` — Flag indicating if the query plan is for a field type

**Key file:** `engine/api/.../CompleteSelectionSetOptions.kt`

## Testing

- `QueryPlanBuildFromSelectionsTest` — `QueryPlanFactory.buildFromSelections()` behavior
- `SubqueryExecutionTest` — end-to-end tests including variable isolation, error handling, nested subqueries, and handle extraction

## References

- [`context-flow.md`](../engine/runtime/impldocs/context-flow.md) — ExecutionHandle and EEC architecture
