# Executor Error Boundaries

The Viaduct engine calls tenant-written resolvers through a set of Executor SPI implementations. The **invariant** this system enforces is: aside from coroutine cancellation (`CancellationException`), every exception that exits an executor entry point is *attributed*. At executor boundaries, tenant-attributed failures are normalized to `TenantResolverException`; framework-attributed failures exit as `FrameworkException`. No raw, unclassified failure crosses the executor/engine boundary.

`handleTenantErrorsSuspend` (and its synchronous equivalent) is used at direct call sites that cross from framework-owned executor code into tenant-written resolver code. Framework-owned code in these executors is not eagerly wrapped with `handleFrameworkErrors*`; later framework boundaries are expected to classify those failures.

This document describes the exception hierarchy, the handler functions and their semantics, the direct tenant-boundary pattern, how `InvocationTargetException` is handled, and how attributed exceptions eventually surface in GraphQL error responses.

## Exception Hierarchy

There are two independent marker interfaces. They are **not** related by inheritance.

### `PassthroughException`

Marks exceptions that have already been classified and should propagate through error boundaries without handler-driven re-wrapping.

| Class | Description |
|---|---|
| `FrameworkException` | A bug in framework code; not attributable to tenant |
| `TenantResolverException` | Wraps an arbitrary exception thrown by a tenant resolver; carries the resolver name and a `resolversCallChain` for nested resolver calls. Error handlers pass it through unchanged, but framework code may explicitly construct a new `TenantResolverException` around an existing one when it needs to extend the resolver call chain. |
| `FieldFetchingException` | Wraps field-level data-fetcher exceptions in the hybrid engine interop path |

### `TenantException`

Exceptions that indicate a misuse of the Tenant API by tenant code. These exceptions are **not** `PassthroughException`. Framework entry points preserve them as-is so tenant misuse is not mislabeled as a framework bug. Executor tenant boundaries, however, intentionally wrap them in `TenantResolverException` so executor outputs are normalized to framework-vs-tenant attribution.

| Class | Description |
|---|---|
| `TenantUsageException` | Invalid API usage by tenant, detected by the framework |
| `UnsetFieldException` | Tenant accessed a field not in the required selection set |
| `ErroneousFieldException` | Tenant accessed a field that is in an error state; carries `graphQLErrors: List<GraphQLError>` which must not be discarded |

`TenantException` is therefore preserved by framework boundaries but normalized by tenant boundaries (see [Handler Semantics](#handler-semantics) below). `TenantResolverException` itself remains passthrough, and may be explicitly re-wrapped at a framework call site when code intentionally wants to extend the resolver call chain.

## Error Handler Functions

The four handler functions in `core/errors/src/main/kotlin/viaduct/errors/Exceptions.kt` are the primary mechanism by which attribution is enforced. Each function wraps a block of code and guarantees that no unattributed exception can exit it:

| Function | Used by | On unattributed exception, wraps as |
|---|---|---|
| `handleFrameworkErrors` | Synchronous framework entry points | `FrameworkException` |
| `handleFrameworkErrorsSuspend` | Suspend framework entry points | `FrameworkException` |
| `handleTenantErrors` | Synchronous tenant call sites | `TenantResolverException` |
| `handleTenantErrorsSuspend` | Suspend tenant call sites | `TenantResolverException` |

In the executor layer, the key requirement is that direct framework-to-tenant call sites use `handleTenantErrors*`. Framework-side validation and result-shaping code may still throw raw framework exceptions or `TenantException`s and rely on later framework boundaries to classify them.

### Handler Semantics

Framework handlers and tenant handlers intentionally use different passthrough rules:

```kotlin
// Framework handlers
if (e is PassthroughException || e is TenantException) throw e
throw FrameworkException("$message ($e)", e)

// Tenant handlers
if (e is PassthroughException) throw e
throw TenantResolverException(e, opName)
```

This means:
- `FrameworkException` passes through tenant boundaries — a framework bug inside a resolver does not get mislabeled as a tenant error.
- `TenantUsageException` (and subclasses) passes through framework boundaries — framework-detected API misuse does not get wrapped as a `FrameworkException`.
- `TenantUsageException` thrown while crossing a tenant boundary is wrapped as `TenantResolverException` so the executor exits with a normalized tenant-attributed wrapper.
- Suspend handlers also call `ensureActive()` on `CancellationException`, so coroutine cancellation continues to propagate unchanged instead of being attributed.
- Already-attributed passthrough exceptions (`TenantResolverException`, `FieldFetchingException`, etc.) are not re-wrapped by the handlers themselves.

## Direct Tenant Boundary Pattern

Each reflective executor call site unwraps the `InvocationTargetException` added by Kotlin reflection, then lets `handleTenantErrorsSuspend` classify the underlying exception:

```
// framework code: build contexts, get provider, etc.
callResolverAndHandleTenantErrors(resolverName, reflectionCall, resolver, ctx)
// framework code: unwrap results, validate batch sizes, etc.
```

This helper sequence still defines the direct framework-to-tenant boundary: the reflective call into tenant resolver code is wrapped by `handleTenantErrorsSuspend`, but the reflection-specific `InvocationTargetException` wrapper is stripped first at the call site. Any unexpected underlying exception from the tenant resolver, including `TenantException`, becomes a `TenantResolverException` carrying the resolver name.

The **inner** `handleTenantErrorsSuspend` boundary covers the reflective call into tenant resolver code. Any unexpected exception from the tenant resolver, including `TenantException`, becomes a `TenantResolverException` carrying the resolver name.

The five executor implementations where this pattern applies:

| Executor | Resolver type |
|---|---|
| `FieldBatchResolverExecutorImpl` | Batched field resolvers |
| `FieldUnbatchedResolverExecutorImpl` | Single-invocation field resolvers |
| `NodeBatchResolverExecutorImpl` | Batched node resolvers |
| `NodeUnbatchedResolverExecutorImpl` | Single-invocation node resolvers |

`VariablesProviderExecutor` still uses `handleTenantErrorsSuspend`, but it does not need the reflection-specific unwrapping helper because it calls tenant code directly rather than through `KFunction.callSuspend`.

### Batch Result Unwrapping

The batch executors (`FieldBatchResolverExecutorImpl`, `NodeBatchResolverExecutorImpl`) call tenant code that returns `List<FieldValue<T>>`. Each `FieldValue` can hold either a success or an error. The `unwrap()` helper calls `fieldValue.get()` to extract the value or rethrow the stored exception. If that rethrows a `TenantException`, the executor wraps it as `TenantResolverException` before returning `Result.failure(...)`, preserving tenant attribution while normalizing the executor surface.

## `InvocationTargetException` Unwrapping

Kotlin reflection (`KFunction.callSuspend`) wraps exceptions thrown by the callee in `InvocationTargetException`. Without unwrapping, the `InvocationTargetException` itself would arrive at `handleTenantErrorsSuspend` rather than the underlying tenant/framework exception. Since the wrapper is neither a `PassthroughException` nor a `TenantException`, it would be wrapped in `TenantResolverException` with the real cause buried one level deeper, making the passthrough checks on the inner exception irrelevant.

The unwrapping is done surgically in the executor-side helper used by each reflection call site, rather than in the handler function itself:

```kotlin
handleTenantErrorsSuspend(resolverName) {
    try {
        resolveFn.callSuspend(resolver, ctx)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }
}
```

`e.targetException` is typed as `Throwable`. By rethrowing it directly, any `Exception` target is caught by `handleTenantErrorsSuspend`'s `catch (e: Exception)` and classified normally. Non-`Exception` throwables (e.g., `Error`) propagate past the handler unchanged.

## Exception Surface in GraphQL Responses

Attributed exceptions ultimately reach `ViaductDataFetcherExceptionHandler`, which maps them to GraphQL errors. The `isFrameworkError` metadata field is determined by inspecting the exception type:

```kotlin
val isFrameworkError = when (exception) {
    is FrameworkException -> true
    is TenantResolverException -> false
    is TenantException -> false
    else -> null  // e.g. FieldFetchingException — hybrid interop path
}
```

Note that field resolver errors are additionally wrapped by the engine in `FieldFetchingException` before reaching this handler. `FieldFetchingException` matches `else -> null`, so `isFrameworkError` will be `null` (not `"false"`) for errors originating in field resolvers even when the executor had already normalized the underlying cause to `TenantResolverException`. This is expected behavior for the current hybrid interop path and is **not** a sign of misattribution.

## Key Files

- `core/errors/src/main/kotlin/viaduct/errors/Exceptions.kt` — Exception hierarchy and handler functions
- `core/errors/api/errors.api` — BCV API dump; must be updated when `@StableApi` types in `Exceptions.kt` change
- `core/tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/execution/FieldBatchResolverExecutorImpl.kt`
- `core/tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/execution/FieldUnbatchedResolverExecutorImpl.kt`
- `core/tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/execution/NodeBatchResolverExecutorImpl.kt`
- `core/tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/execution/NodeUnbatchedResolverExecutorImpl.kt`
- `core/tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/execution/TenantResolverInvocation.kt` — Shared helper for reflective resolver invocation plus `InvocationTargetException` unwrapping
- `core/tenant/runtime/src/main/kotlin/viaduct/tenant/runtime/execution/VariablesProviderExecutor.kt`
- `core/engine/runtime/src/main/kotlin/viaduct/engine/runtime/execution/ViaductDataFetcherExceptionHandler.kt` — Maps attributed exceptions to GraphQL errors
- `core/tenant/api/src/main/kotlin/viaduct/api/internal/ObjectBase.kt` — Passthrough checks in `fetch()` / `get()` catch blocks

## Testing

- `core/tenant/runtime/src/testFixtures/kotlin/viaduct/tenant/runtime/fixtures/TenantExceptionWrappingContractTest.kt` — Contract coverage for normalizing node-batch `TenantUsageException` to `TenantResolverException`
- `core/tenant/runtime/src/test/kotlin/viaduct/tenant/runtime/execution/batchresolver/tenantexceptionpassthrough/KotlinTenantExceptionWrappingContractTest.kt` — Kotlin runtime implementation of that contract
- `core/tenant/api/src/test/kotlin/viaduct/api/TenantResolverExceptionTest` — Unit tests for `handleTenantErrorsSuspend` wrapping behavior
