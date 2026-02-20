# Selection Execution via ExecutionHandle

Resolvers sometimes need to ask follow-up questions of the graph.

A tenant resolver might load an object, then need to run an ad-hoc query against that object or the root schema. The naive approach would be to build a new GraphQL-Java execution for each of these "subqueries," but that throws away all of the state the engine already has for the current request.

The selection execution path (`ctx.query()` / `ctx.mutation()`) is the engine's way of doing this without rebuilding everything. It reuses the existing execution context through an opaque `ExecutionHandle`, and runs the selection through the same execution pipeline as the parent query.

This document follows a selection execution from the resolver's `ctx.query()` call all the way through execution and back.

## Terminology

- **Selection Execution**: An internal engine call issued from a resolver that runs against the same request and execution state, rather than starting a new GraphQL-Java execution. Sometimes called "subquery" in informal discussion.
- **Tenant vs Engine**: "Tenant" refers to the generated resolver layer and its types (`Context`, `SelectionSet<Query>`). "Engine" refers to the shared execution core (planning, field resolution, access checks) that powers all tenants.
- **ExecutionHandle**: An opaque reference to the parent request's `ExecutionParameters`. Used to recover engine state when running selection executions.
- **EngineSelectionSet**: The engine's untyped representation of selections, variables, and fragments—what the planner actually consumes.
- **GRT objects**: Generated, strongly-typed GraphQL Representational Types (e.g., `Query`, `Mutation`) returned to tenant resolvers.
- **EEC**: `EngineExecutionContext`, the request-scoped context that provides access to schema, execution state, and the `resolveSelectionSet()` API.

## Three-Tier Architecture

Selection execution uses a three-tier API architecture:

| Tier | API | Purpose | Consumers |
|------|-----|---------|-----------|
| **Tenant** | `ctx.query(SelectionSet<T>)` / `ctx.mutation(SelectionSet<T>)` | Typed, simple, opinionated | Resolver code |
| **Engine API** | `EEC.resolveSelectionSet(resolverId, selectionSet, options)` | Flexible, configurable, triggers resolution | Advanced tenant runtime integrations, engine internals |
| **Engine API** | `EEC.completeSelectionSet(selectionSet, arguments, options)` | Complete already-resolved fields | Classic-on-Modern shims path |
| **Wiring** | `Engine.resolveSelectionSet(handle, selectionSet, options)` | Implementation detail | Only called by EEC |
| **Wiring** | `Engine.completeSelectionSet(handle, selectionSet, ...)` | Implementation detail | Only called by EEC |

This layering provides:
- Simple APIs for common cases (tenant layer)
- Flexibility for advanced use cases (via `ResolveSelectionSetOptions`)
- Clear separation of concerns (EEC handles validation and delegates to Engine)

## When to Use Subqueries

Typical use cases for `ctx.query()` / `ctx.mutation()`:

- Selecting additional fields based on runtime data (e.g., only fetch expensive fields if a previous check passes)
- Fetching fields from related types that aren't part of the current resolver's return type (e.g., loading user details when resolving a reservation)
- Reusing existing schema logic instead of reimplementing it in tenant code

For declarative, static sibling/root fields known at registration time, prefer `querySelections(...)` instead (see Comparison section).

## Overview

```
┌─────────────────────────────────────┐
│ Step 1: Tenant Resolver             │
│ ctx.query(selections)               │
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
│ ExecutionParameters.forResolution()   │
│ QueryPlan.buildFromSelections()     │
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

From a resolver, subquery execution starts with `ctx.query()` or `ctx.mutation()`. The resolver builds a typed `SelectionSet<T>` via `ctx.selectionsFor(type, selectionString, variables)`, then passes it to `ctx.query()`.

The selection string is parsed as GraphQL selection syntax (fields, arguments, inline fragments). You can use GraphQL variable syntax like `$var` in the selection string, but the values come from the `variables` map you pass to `selectionsFor`—not from the parent request's variables.

`ctx.mutation()` works the same way but is only available in mutation resolvers — the generated tenant API doesn't expose `mutation()` on query resolver contexts, so attempting to call it is a compile-time error.

Nested subqueries are supported and run within the same parent execution handle. Mutation resolvers can call query subqueries freely. Note that while the tenant API prevents query resolvers from calling mutation subqueries (compile-time), the engine layer itself does not enforce this restriction — code that bypasses the tenant API could still do so.

## Step 2: The Tenant Runtime Bridge

The `Context` type that resolvers see is generated from the resolver base class. At runtime, these are implementations that extend `ResolverExecutionContextImpl`.

When a resolver calls `ctx.query(selections)`, the call flows through the bridge:

1. `ResolverExecutionContextImpl.query()` delegates to `EngineExecutionContextWrapperImpl.query()`
2. The wrapper converts the tenant's `SelectionSet<T>` into an `EngineSelectionSet`
3. It calls `EngineExecutionContext.resolveSelectionSet(resolverId, engineSelectionSet, options)`

This bridge is the only place the tenant runtime touches the engine. It converts:
- **To engine**: `SelectionSet<Query>` → `EngineSelectionSet` (the `ExecutionHandle` is accessed internally)
- **Back to tenant**: `EngineObjectData` → typed GRT objects (via `toObjectGRT()`)

**Key files:**
- `tenant/runtime/.../context/ResolverExecutionContextImpl.kt` — tenant-facing `query()` method
- `tenant/runtime/.../context/EngineExecutionContextWrapper.kt` — bridge implementation

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
3. Calls `parentParams.forResolution(selectionSet, targetOER)` to build child execution parameters
4. Runs the field-resolution pipeline and wraps the result

The `ExecutionHandle` is deliberately opaque -- tenant code sees `EngineExecutionContext.ExecutionHandle`, not `ExecutionParameters`. A handle is tied to the engine instance and request that created it; it cannot be reused across requests or engine instances.

Inside the runtime module, `asExecutionParameters()` bridges that gap. If someone fabricates a handle that isn't an `ExecutionParameters`, the cast fails with `SubqueryExecutionException.invalidExecutionHandle()`.

**Key files:**
- `engine/api/.../Engine.kt` — `resolveSelectionSet()` interface
- `engine/wiring/.../EngineImpl.kt` — implementation
- `engine/runtime/.../execution/ExecutionHandleExtensions.kt` — handle extraction

## Step 5: Building Child Execution Parameters

The core of subquery execution is `ExecutionParameters.forResolution()`. This method builds `QueryPlan.Parameters` using `fullSchema`, calls `QueryPlan.buildFromSelections()` to create the plan, then delegates to `forChildPlan()`.

### Schema Choice

Subqueries always use `fullSchema`, not `activeSchema`. The active schema can be a restricted view (for introspection or scoped concerns), but subqueries are internal server-side calls. When a resolver issues a subquery, it's asking the engine to consult the full graph, not mimic a client's restricted view.

### Variable Scoping

Subqueries do not inherit variables from the parent request. Variables come only from the subquery's own `EngineSelectionSet`, which is derived from the tenant's `SelectionSet<T>`.

This means:
- Two subqueries with identical selection strings but different `variables` maps remain independent
- Subquery variables don't leak back to the parent
- Changes to parent request variables cannot affect subquery behavior

### Memoization Control

The `targetResult` option controls memoization. `ObjectEngineResultImpl` holds resolved field results. By choosing which instance to pass:

- Fresh `ObjectEngineResultImpl` → isolated execution, no shared memoization
- Existing `ObjectEngineResultImpl` → selections share already-resolved fields

The tenant-facing `ctx.query()` and `ctx.mutation()` always create fresh instances, so selections issued through those APIs are isolated by default. The lower-level `EEC.resolveSelectionSet()` with custom `targetResult` enables shared memoization for advanced use cases.

### Building the QueryPlan

Subqueries don't start from a full GraphQL document—they start from an `EngineSelectionSet` that already contains the parent type, selection AST, fragment definitions, and variables. `QueryPlan.buildFromSelections()` feeds this directly into the plan builder, skipping re-parsing and document construction.

Plan caching keys on selection text, document key, schema hash, and `executeAccessChecksInModstrat`. Variables are not part of the cache key—the plan only depends on field/argument structure, not specific values.

**Key files:**
- `engine/runtime/.../execution/ExecutionParameters.kt` — `forResolution()` method
- `engine/runtime/.../execution/QueryPlan.kt` — `buildFromSelections()`

## Step 6: Field Resolution

Once `forResolution()` produces child `ExecutionParameters` and a `QueryPlan`, the wiring layer runs the standard field-resolution pipeline:

- `fieldResolver.fetchObject()` for queries
- `fieldResolver.fetchObjectSerially()` for mutations

Selections always execute "as root"—`isRootQueryQueryPlan = true`, source is the execution root, and `parentFieldStepInfo` is `null`. This means the selection sees the same root object and request-level context as the original query, but it is not nested under the parent field in the query plan. This affects logging/tracing (it appears as a separate root execution) but not authorization or data loader scoping.

Results are stored in the provided `targetOER`, and a `ProxyEngineObjectData` wraps the result.

## Step 7: Result Conversion

Back in `EngineExecutionContextWrapperImpl`, the `EngineObjectData` result is converted to a typed GRT object via `toObjectGRT()`. The resolver receives a strongly-typed `Query` or `Mutation` object with accessor methods for the selected fields.

## Error Handling

Selection execution wraps failures in `SubqueryExecutionException`:

- **Missing handle**: `resolveSelectionSet()` throws immediately if `executionHandle` is null
- **Invalid handle**: `asExecutionParameters()` throws `invalidExecutionHandle()` if the handle isn't an `ExecutionParameters`
- **Selection type mismatch**: If the `EngineSelectionSet.type` doesn't match the root type for the operation (e.g., passing a `User` selection to a Query subquery)
- **Plan build issues**: Wrapped in `queryPlanBuildFailed(e)`
- **Field resolution failures**: Wrapped in `fieldResolutionFailed(e)`

Note that `EngineSelectionSet.Empty` throws `IllegalArgumentException` (not `SubqueryExecutionException`) since it represents a programmer error rather than a runtime failure.

Each `ExecutionParameters` has its own `ErrorAccumulator`, so selection errors flow back into `EngineResult.errors` with correct attribution. From the tenant side, failures surface as errors on the returned GRT object's result, just like top-level execution errors.

**Key file:** `engine/api/.../SubqueryExecutionException.kt`

## Comparison with Other Patterns

| Pattern | Use Case | Mechanism |
|---------|----------|-----------|
| `querySelections("field")` | Declarative sibling/root fields | Registered as child plans, executed with root query plan |
| `ctx.query(selections)` | Dynamic selections | Executes via ExecutionHandle |
| `EEC.resolveSelectionSet(options)` | Advanced use cases | Configurable execution options |
| `EEC.completeSelectionSet(...)` | Completing already-resolved fields | No field resolution, just completion |

Use `querySelections` when fields are known at registration time—it's simpler and more efficient. Use `ctx.query()` when selections depend on runtime data. Use `EEC.resolveSelectionSet()` with custom options for advanced tenant runtime integrations.

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
val data = eec.resolveSelectionSet(resolverId, selectionSet, options)
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

- `QueryPlanBuildFromSelectionsTest` — `QueryPlan.buildFromSelections()` behavior
- `SubqueryExecutionTest` — end-to-end tests including variable isolation, error handling, nested subqueries, and handle extraction

## References

- [`context-flow.md`](../engine/runtime/impldocs/context-flow.md) — ExecutionHandle and EEC architecture
