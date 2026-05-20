# Viaduct Exception Hierarchy Specification

## Overview

This document describes the clarified exception hierarchy for Viaduct's error classification and propagation system.

## Hierarchy

There are two independent marker interfaces. They are **not** related by inheritance.

### `TenantException` (standalone marker interface)

Marks exceptions that classify an error as caused by tenant code. These exceptions are **not** `PassthroughException` — they represent detected API misuse or data errors, not already-classified propagation wrappers.

| Class | Description |
|---|---|
| `TenantUsageException` | Invalid API usage by tenant, detected by the framework |
| `UnsetFieldException` | Tenant accessed a field not in the required selection set |
| `ErroneousFieldException` | Tenant accessed a selected field that is in an error state |

Use this exception when "in framework code" to flag detected tenant errors: these will pass through `handleFrameworkError` and will get wrapped by `handleTenantError`.  Do **not** use these when "in tenant code:" instead use "natural" exceptions like `IllegalArgumentException` or `NoSuchElementException`.

### `PassthroughException` (standalone marker interface)

Marks exceptions that have already been classified and should propagate through error boundaries without being re-wrapped.

| Class | Description |
|---|---|
| `FrameworkException` | A bug in framework code; should not be attributed to tenant |
| `TenantResolverException` | Wraps an arbitrary exception thrown by a tenant resolver |
| `FieldFetchingException` | Wraps exceptions from data fetchers (hybrid interop) |

Note: The `TenantException` group is also treated as passthrough by the framework error handler — see below.

## Error Handler Semantics

The two error boundary functions handle these differently:

```kotlin
// In handleFrameworkErrors / handleFrameworkErrorsSuspend:
if (e is PassthroughException || e is TenantException) throw e
throw FrameworkException("$message ($e)", e)

// In handleTenantErrors / handleTenantErrorsSuspend (after InvocationTargetException unwrap):
if (e is PassthroughException) throw e
throw TenantResolverException(e, opName)
```

This means:

- `TenantUsageException` (and subclasses) pass through framework boundaries without being wrapped in `FrameworkException`
- `TenantUsageException` thrown inside tenant code is wrapped by `handleTenantErrors` into `TenantResolverException`, attributing it to the tenant
