# Context Flow in Viaduct Engine

This document explains how execution context flows through the Viaduct engine, including when different context mechanisms are available and why certain patterns exist.

## Overview

Viaduct uses several layered context mechanisms to pass execution state:

1. **EngineExecutionContext (EEC)** - Viaduct's primary context, containing request-scoped and field-scoped state
2. **Local Context** - graphql-java's mechanism for passing context through the execution tree
3. **DataFetchingEnvironment (DFE)** - graphql-java's per-field context, wrapped by Viaduct as `ViaductDataFetchingEnvironment`
4. **ExecutionParameters** - Viaduct's modern execution strategy's position in the execution tree

## Context Lifecycle

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  EngineImpl.execute()                                                       │
│  ├── mkEngineExecutionContext() ──► Creates EEC                             │
│  └── Stores EEC in graphql-java's localContext                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  graphql-java instrumentation hooks                                         │
│  ├── instrumentExecutionContext() ──► EEC available via localContext        │
│  │                                    ⚠️ NO DFE EXISTS YET                  │
│  └── executeOperation()                                                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  ViaductExecutionStrategy.execute()                                         │
│  ├── Retrieves EEC from localContext                                        │
│  ├── Creates ExecutionParameters (copies EEC, sets execution handle)        │
│  └── Field execution begins                                                 │
│      ├── DFE created per-field                                              │
│      └── ViaductDataFetchingEnvironment wraps DFE + EEC                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

## EngineExecutionContext (EEC)

The EEC is Viaduct's primary execution context, containing three types of state:

### State Scopes

| Scope | Lifetime | Examples |
|-------|----------|----------|
| **View-scoped** | Rarely changes (schema updates) | `fullSchema`, `scopedSchema`, `rawSelectionSetFactory` |
| **Request-scoped** | Once per GraphQL request | `requestContext`, `engine`, `dispatcherRegistry` |
| **Field-scoped** | Changes during tree traversal | `fieldScope` (fragments, variables, resolutionPolicy) |

### Creation

EEC is created **once per request** in `EngineImpl.mkEngineExecutionContext()`:

```kotlin
// EngineImpl.kt
private fun mkGJExecutionInput(executionInput: ExecutionInput): GJExecutionInput {
    val localContext = CompositeLocalContext.withContexts(mkEngineExecutionContext(executionInput.requestContext))
    return executionInputBuilder
        .localContext(localContext)
        .build()
}
```

### Why EEC Must Be Created Before Execution Starts

`ScopeInstrumentation` runs in `instrumentExecutionContext()` - a graphql-java hook that fires **before any field execution**. At this point:
- ✅ `ExecutionContext` exists (with localContext containing EEC)
- ❌ No `DataFetchingEnvironment` exists yet

This is why ScopeInstrumentation must use `findLocalContextForType<EngineExecutionContextImpl>()` to retrieve the EEC - there's no DFE to get it from.

## Accessing EEC: Local Context vs DFE Extension

### When to Use Local Context Lookup

Use `findLocalContextForType<EngineExecutionContextImpl>()` when:
- In instrumentation hooks (`instrumentExecutionContext`, `instrumentSchema`)
- In execution strategies before DFE is created
- In code that runs at the operation level, not field level

Examples:
- `ScopeInstrumentation` - swaps schema for introspection
- `ViaductExecutionStrategy` - creates initial ExecutionParameters
- `ResolverRewriterStrategy` - legacy execution path

### When to Use DFE Extension

Use `dfe.engineExecutionContext` when:
- Inside data fetchers
- In resolver implementations
- Anywhere a `DataFetchingEnvironment` is available

```kotlin
// Preferred approach when DFE is available
val eec = dataFetchingEnvironment.engineExecutionContext
```

## The Execution Handle

The `EngineExecutionContext.ExecutionHandle` is an **opaque handle** representing an ongoing execution. It enables subquery execution (e.g., `ctx.query()`) without tenant code needing to understand execution internals.

### Why It Exists

When tenant code calls `ctx.query(selections)` to execute a subquery, the engine needs to:
1. Know which `ExecutionParameters` the request came from
2. Access the current execution's coroutine scope, error accumulator, instrumentation, etc.
3. Maintain proper parent-child relationships for error attribution

Without the handle, tenants would need to pass around `ExecutionParameters` directly—exposing internal execution machinery and creating a fragile API.

### Opaqueness by Design

The handle is deliberately opaque:

```kotlin
// In EngineExecutionContext (API layer)
interface ExecutionHandle  // Empty marker interface

// Tenant code sees only this:
val executionHandle: ExecutionHandle?
```

The actual implementation is `ExecutionParameters`, but tenant code doesn't know or care:

```kotlin
// In ExecutionParameters (runtime layer)
data class ExecutionParameters(...) : EngineExecutionContext.ExecutionHandle
```

This separation allows:
- **API stability** - Tenants depend on the stable `EngineExecutionContext` interface
- **Implementation freedom** - Runtime can change `ExecutionParameters` internals without breaking tenants
- **Encapsulation** - Tenants can't accidentally misuse execution state

### Handle Lifecycle

1. **Created**: When `ExecutionParameters` is instantiated
2. **Linked to EEC**: Lazily, when `parameters.engineExecutionContext` is first accessed
3. **Propagated**: Each derived EEC maintains the handle pointing to its owning `ExecutionParameters`

```kotlin
// The handle is set lazily on access
val engineExecutionContext: EngineExecutionContext
    get() {
        _engineExecutionContext.setExecutionHandle(this)  // 'this' is ExecutionParameters
        return _engineExecutionContext
    }
```

### Extracting ExecutionParameters from Handle

Within the runtime module, code can recover `ExecutionParameters` from a handle:

```kotlin
// ExecutionHandleExtensions.kt
internal fun EngineExecutionContext.ExecutionHandle?.asExecutionParameters(): ExecutionParameters? = 
    this as? ExecutionParameters
```

This is used by the engine when processing subquery requests—the tenant passes EEC (with handle), and the engine extracts the `ExecutionParameters` to continue execution.

## ExecutionParameters and EEC Derivation

`ExecutionParameters` represents a position in the execution tree and owns its own EEC copy.

### Key Design Decisions

1. **Each ExecutionParameters gets its own EEC copy** (in `init` block):
   ```kotlin
   init {
       _engineExecutionContext = _engineExecutionContext.copy()
   }
   ```

2. **Lazy handle setting** - The `executionHandle` is set when `engineExecutionContext` is accessed:
   ```kotlin
   init {
       _engineExecutionContext = _engineExecutionContext.copy()
       _engineExecutionContext.setExecutionHandle(this)
   }

   val engineExecutionContext: EngineExecutionContext
       get() = _engineExecutionContext
   ```

3. **Single copy method** - Use `eec.copy()` everywhere to create derived contexts:
   ```kotlin
   val derivedEec = parameters.engineExecutionContext.copy(
       fieldScopeSupplier = { FieldExecutionScopeImpl(childFragments, childVariables) }
   )
   ```
   The copy automatically preserves the execution handle.

## ViaductDataFetchingEnvironment

Bridges graphql-java's `DataFetchingEnvironment` with Viaduct's `EngineExecutionContext`:

```kotlin
interface ViaductDataFetchingEnvironment : DataFetchingEnvironment {
    val engineExecutionContext: EngineExecutionContext
}
```

Created in `FieldExecutionHelpers.mkViaductDataFetchingEnvironment()`:
1. Derives EEC with field-specific scope
2. Creates `ViaductDataFetchingEnvironmentImpl` wrapping both DFE and EEC
3. Sets bidirectional link: EEC.dataFetchingEnvironment = vdfe

## Common Patterns

### Retrieving EEC in Instrumentation (No DFE)

```kotlin
// ScopeInstrumentation.kt
override fun instrumentExecutionContext(
    executionContext: ExecutionContext,
    parameters: InstrumentationExecutionParameters,
    state: InstrumentationState?
): ExecutionContext {
    val eec = executionContext.findLocalContextForType<EngineExecutionContextImpl>()
    // ... use eec
}
```

### Retrieving EEC in Data Fetchers

```kotlin
// In any data fetcher or resolver
val eec = dataFetchingEnvironment.engineExecutionContext
val fragments = eec.fieldScope.fragments
val variables = eec.fieldScope.variables
```

### Deriving EEC for Child Execution

```kotlin
// Anywhere you need a derived EEC
val childEec = parameters.engineExecutionContext.copy(
    fieldScopeSupplier = { FieldExecutionScopeImpl(childFragments, childVariables) }
)
```

## Summary

- **EEC must be created before graphql-java execution** because `ScopeInstrumentation` needs it in `instrumentExecutionContext()`
- **Use local context lookup** when no DFE exists (instrumentation, execution strategies)
- **Use `dfe.engineExecutionContext`** when DFE is available (data fetchers, resolvers)
- **Use `eec.copy()`** to create derived contexts - copies automatically preserve the execution handle
