# Selection Execution via ExecutionHandle

Resolvers sometimes need to ask follow-up questions of the graph.

A tenant resolver might load an object, then need to run an ad-hoc query against that object or the root schema. The naive approach would be to build a new GraphQL-Java execution for each of these "subqueries," but that throws away all of the state the engine already has for the current request.

The selection execution path (`ctx.query()` / `ctx.mutation()`) is the engine's way of doing this without rebuilding everything. It reuses the existing execution context through an opaque `ExecutionHandle`, and runs the selection through the same execution pipeline as the parent query.

This document follows a selection execution from the resolver's `ctx.query()` call all the way through execution and back.

## Terminology

- **Selection Execution**: An internal engine call issued from a resolver that runs against the same request and execution state, rather than starting a new GraphQL-Java execution. Sometimes called "subquery" in informal discussion.
- **Tenant vs Engine**: "Tenant" refers to the generated resolver layer and its types (`Context`, `SelectionSet<Query>`). "Engine" refers to the shared execution core (planning, field resolution, access checks) that powers all tenants.
- **ExecutionHandle**: An opaque reference to the parent request's `ExecutionParameters`. Used to recover engine state when running selection executions.
- **RawSelectionSet**: The engine's untyped representation of selections, variables, and fragments—what the planner actually consumes.
- **GRT objects**: Generated, strongly-typed GraphQL Representational Types (e.g., `Query`, `Mutation`) returned to tenant resolvers.
- **EEC**: `EngineExecutionContext`, the request-scoped context that provides access to schema, execution state, and the `executeSelectionSet()` API.

## Three-Tier Architecture

Selection execution uses a three-tier API architecture:

| Tier | API | Purpose | Consumers |
|------|-----|---------|-----------|
| **Tenant** | `ctx.query(SelectionSet<T>)` / `ctx.mutation(SelectionSet<T>)` | Typed, simple, opinionated | Resolver code |
| **Engine API** | `EEC.executeSelectionSet(resolverId, selectionSet, options)` | Flexible, configurable | Advanced tenant runtime integrations, engine internals |
| **Wiring** | `Engine.executeSelectionSet(handle, selectionSet, options)` | Implementation detail | Only called by EEC |

This layering provides:
- Simple APIs for common cases (tenant layer)
- Flexibility for advanced use cases (via `ExecuteSelectionSetOptions`)
- Clear separation of concerns (EEC handles flag checks and fallback logic)

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
│ EEC.executeSelectionSet()              │
│ (flag check + fallback logic)       │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Step 4: Wiring Layer                │
│ Engine.executeSelectionSet()           │
└─────────────────┬───────────────────┘
                  │
                  ▼
┌─────────────────────────────────────┐
│ Step 5: Build Child Parameters      │
│ ExecutionParameters.forSubquery()   │
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
2. The wrapper converts the tenant's `SelectionSet<T>` into a `RawSelectionSet`
3. It calls `EngineExecutionContext.executeSelectionSet(resolverId, rawSelectionSet, options)`

This bridge is the only place the tenant runtime touches the engine. It converts:
- **To engine**: `SelectionSet<Query>` → `RawSelectionSet` (the `ExecutionHandle` is accessed internally)
- **Back to tenant**: `EngineObjectData` → typed GRT objects (via `toObjectGRT()`)

**Key files:**
- `tenant/runtime/.../context/ResolverExecutionContextImpl.kt` — tenant-facing `query()` method
- `tenant/runtime/.../context/EngineExecutionContextWrapper.kt` — bridge implementation

## Step 3: The Engine API Layer

The Engine API layer is `EngineExecutionContextImpl.executeSelectionSet()`. This is where the decision logic lives:

1. Check if advanced options require a valid handle -- fail fast with `SubqueryExecutionException` if unavailable
2. If `ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE` flag is enabled and handle is available, use handle-based execution
3. Otherwise, fall back to the legacy `RawSelectionsLoader` path (for basic options only)

The tenant wrapper calls `executeSelectionSet()` with `ExecuteSelectionSetOptions.DEFAULT` for queries or `ExecuteSelectionSetOptions.MUTATION` for mutations.

### ExecuteSelectionSetOptions

`ExecuteSelectionSetOptions` provides flexibility for advanced use cases. See `engine/api/.../ExecuteSelectionSetOptions.kt` for the full definition.

Options:
- `operationType` — Query or Mutation (default: Query)
- `targetResult` — Memoization control (default: fresh instance)

The `targetResult` option requires both a valid `executionHandle` and the `ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE` flag. If these conditions are not met, `executeSelectionSet()` throws immediately rather than silently degrading.

**Key files:**
- `engine/api/.../ExecuteSelectionSetOptions.kt` — options definition
- `engine/runtime/.../EngineExecutionContextImpl.kt` — `executeSelectionSet()` implementation

## Step 4: The Wiring Layer

The wiring layer is `EngineImpl.executeSelectionSet()`. It takes the opaque `ExecutionHandle`, a `RawSelectionSet`, and an `ExecuteSelectionSetOptions` instance that carries the operation type and optional target `ObjectEngineResult`.

This method:

1. Recovers the parent `ExecutionParameters` from the handle via `asExecutionParameters()`
2. Looks up the root type (`queryType` or `mutationType`) from `fullSchema`
3. Calls `parentParams.forSubquery(selectionSet, targetOER)` to build child execution parameters
4. Runs the field-resolution pipeline and wraps the result

The `ExecutionHandle` is deliberately opaque -- tenant code sees `EngineExecutionContext.ExecutionHandle`, not `ExecutionParameters`. A handle is tied to the engine instance and request that created it; it cannot be reused across requests or engine instances.

Inside the runtime module, `asExecutionParameters()` bridges that gap. If someone fabricates a handle that isn't an `ExecutionParameters`, the cast fails with `SubqueryExecutionException.invalidExecutionHandle()`.

**Key files:**
- `engine/api/.../Engine.kt` — `executeSelectionSet()` interface
- `engine/wiring/.../EngineImpl.kt` — implementation
- `engine/runtime/.../execution/ExecutionHandleExtensions.kt` — handle extraction

## Step 5: Building Child Execution Parameters

The core of subquery execution is `ExecutionParameters.forSubquery()`. This method builds `QueryPlan.Parameters` using `fullSchema`, calls `QueryPlan.buildFromSelections()` to create the plan, then delegates to `forChildPlan()`.

### Schema Choice

Subqueries always use `fullSchema`, not `activeSchema`. The active schema can be a restricted view (for introspection or scoped concerns), but subqueries are internal server-side calls. When a resolver issues a subquery, it's asking the engine to consult the full graph, not mimic a client's restricted view.

### Variable Scoping

Subqueries do not inherit variables from the parent request. Variables come only from the subquery's own `RawSelectionSet`, which is derived from the tenant's `SelectionSet<T>`.

This means:
- Two subqueries with identical selection strings but different `variables` maps remain independent
- Subquery variables don't leak back to the parent
- Changes to parent request variables cannot affect subquery behavior

### Memoization Control

The `targetResult` option controls memoization. `ObjectEngineResultImpl` holds resolved field results. By choosing which instance to pass:

- Fresh `ObjectEngineResultImpl` → isolated execution, no shared memoization
- Existing `ObjectEngineResultImpl` → selections share already-resolved fields

The tenant-facing `ctx.query()` and `ctx.mutation()` always create fresh instances, so selections issued through those APIs are isolated by default. The lower-level `EEC.executeSelectionSet()` with custom `targetResult` enables shared memoization for advanced use cases.

### Building the QueryPlan

Subqueries don't start from a full GraphQL document—they start from a `RawSelectionSet` that already contains the parent type, selection AST, fragment definitions, and variables. `QueryPlan.buildFromSelections()` feeds this directly into the plan builder, skipping re-parsing and document construction.

Plan caching keys on selection text, document key, schema hash, and `executeAccessChecksInModstrat`. Variables are not part of the cache key—the plan only depends on field/argument structure, not specific values.

**Key files:**
- `engine/runtime/.../execution/ExecutionParameters.kt` — `forSubquery()` method
- `engine/runtime/.../execution/QueryPlan.kt` — `buildFromSelections()`

## Step 6: Field Resolution

Once `forSubquery()` produces child `ExecutionParameters` and a `QueryPlan`, the wiring layer runs the standard field-resolution pipeline:

- `fieldResolver.fetchObject()` for queries
- `fieldResolver.fetchObjectSerially()` for mutations

Selections always execute "as root"—`isRootQueryQueryPlan = true`, source is the execution root, and `parentFieldStepInfo` is `null`. This means the selection sees the same root object and request-level context as the original query, but it is not nested under the parent field in the query plan. This affects logging/tracing (it appears as a separate root execution) but not authorization or data loader scoping.

Results are stored in the provided `targetOER`, and a `ProxyEngineObjectData` wraps the result.

## Step 7: Result Conversion

Back in `EngineExecutionContextWrapperImpl`, the `EngineObjectData` result is converted to a typed GRT object via `toObjectGRT()`. The resolver receives a strongly-typed `Query` or `Mutation` object with accessor methods for the selected fields.

## Error Handling

Selection execution wraps failures in `SubqueryExecutionException`:

- **Missing handle for advanced options**: `executeSelectionSet()` throws immediately if advanced options require a handle but none is available
- **Invalid handle**: `asExecutionParameters()` throws `invalidExecutionHandle()` if the handle isn't an `ExecutionParameters`
- **Selection type mismatch**: If the `RawSelectionSet.type` doesn't match the root type for the operation (e.g., passing a `User` selection to a Query subquery)
- **Plan build issues**: Wrapped in `queryPlanBuildFailed(e)`
- **Field resolution failures**: Wrapped in `fieldResolutionFailed(e)`

Note that `RawSelectionSet.Empty` throws `IllegalArgumentException` (not `SubqueryExecutionException`) since it represents a programmer error rather than a runtime failure.

Each `ExecutionParameters` has its own `ErrorAccumulator`, so selection errors flow back into `EngineResult.errors` with correct attribution. From the tenant side, failures surface as errors on the returned GRT object's result, just like top-level execution errors.

**Key file:** `engine/api/.../SubqueryExecutionException.kt`

## Comparison with Other Patterns

| Pattern | Use Case | Mechanism |
|---------|----------|-----------|
| `querySelections("field")` | Declarative sibling/root fields | Registered as child plans, executed with root query plan |
| `ctx.query(selections)` | Dynamic selections | Executes via ExecutionHandle |
| `EEC.executeSelectionSet(options)` | Advanced use cases | Configurable execution options |
| `FragmentLoader` | Legacy compatibility | Full GraphQL-Java re-execution |

Use `querySelections` when fields are known at registration time—it's simpler and more efficient. Use `ctx.query()` when selections depend on runtime data. Use `EEC.executeSelectionSet()` with custom options for advanced tenant runtime integrations. `FragmentLoader` remains for existing resolvers that still construct full documents manually; prefer `querySelections` or `ctx.query()` for new code.

## Testing

- `QueryPlanBuildFromSelectionsTest` — `QueryPlan.buildFromSelections()` behavior
- `SubqueryExecutionTest` — end-to-end tests including variable isolation, error handling, nested subqueries, and handle extraction

## References

- [`context-flow.md`](../engine/runtime/impldocs/context-flow.md) — ExecutionHandle and EEC architecture
- `Flags.kt` — `ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE` feature flag for gradual rollout
