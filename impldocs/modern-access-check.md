# Access Checks

The Viaduct engine supports field-level and type-level access checks that run alongside field resolution. When a field or type has a checker configured, the engine executes the check, stores the result in a dedicated slot, and combines it with the resolver result during completion (see [Terminology](#terminology) for slot names).

The execution order depends on operation type: for queries, the checker and resolver run in **parallel**; for mutations and subscriptions, the checker runs **first** and the resolver is only executed if the check passes.

This document covers the engine's access check architecture—how checkers are registered, how query plans incorporate checker data requirements, how checks execute at runtime, and how results flow through completion.

## Terminology

- **CheckerExecutor**: The SPI interface that access-check implementations must satisfy. Handles both field and type checks.
- **CheckerExecutorFactory**: Creates `CheckerExecutor` instances for each field coordinate or type at bootstrap time. Returns `null` for fields/types without checks.
- **CheckerResult**: Sealed interface representing check outcomes—`Success` or `Error`. Errors carry an exception and define how field and type errors combine.
- **CheckerDispatcher**: Runtime wrapper around `CheckerExecutor` that the engine's execution pipeline interacts with.
- **RSS (Required Selection Set)**: Selections a checker needs resolved before it can execute. The engine resolves these and passes the data to the checker as `EngineObjectData`. Checks are **not** run on checker RSS selections.
- **OER (ObjectEngineResult)**: Memoization structure for field results. Each field and list element has two slots: `RAW_VALUE_SLOT` (resolver result) and `ACCESS_CHECK_SLOT` (checker result). This multi-slot design allows us to bypass checks for checker RSSes by reading only from the raw slot.
- **QueryPlan**: Intermediate representation of a GraphQL selection set. Built on first request (then cached), it embeds checker RSS as child plans so the engine knows what data to pre-fetch for checkers.

## Architecture Flow

```
┌──────────────────────────────────────┐
│ 1. Bootstrap (tenant loading)        │
│ CheckerExecutorFactory creates       │
│ CheckerExecutors for each field/type │
│ → DispatcherRegistryFactory wraps    │
│   them in CheckerDispatchers         │
│ → ExecutorValidator validates RSS    │
│ → Dispatchers stored in              │
│   DispatcherRegistry                 │
└──────────────────┬───────────────────┘
                   │
                   ▼
┌──────────────────────────────────────┐
│ 2. QueryPlan build (first request)   │
│ QueryPlanFactory builds plan from    │
│ GraphQL document + schema:           │
│ → For each field, consults           │
│   RequiredSelectionSetRegistry for   │
│   checker RSS                        │
│ → Checker RSS embedded as childPlans │
│   on each Field in the plan          │
│ → Type checker RSS built lazily via  │
│   fieldTypeChildPlans                │
│ → Plan cached (keyed by query)       │
└──────────────────┬───────────────────┘
                   │
                   ▼
┌──────────────────────────────────────┐
│ 3. Field Resolution (FieldResolver)  │
│ For each field:                      │
│  a. Launch childPlans (including     │
│     checker RSS plans) to pre-fetch  │
│     data the checker needs           │
│  b. fieldCheck() starts the field    │
│     access check                     │
│  c. Data fetcher runs:               │
│     • Query: in parallel with check  │
│     • Mutation/Subscription: only    │
│       after check passes             │
│  d. combineWithTypeCheck() merges    │
│     field + type check results into  │
│     a single combinedCheckerResult   │
│  e. Both raw result and combined     │
│     checker result stored in OER     │
│     (RAW_VALUE_SLOT, ACCESS_CHECK_   │
│     SLOT)                            │
└──────────────────┬───────────────────┘
                   │
                   ▼
┌──────────────────────────────────────┐
│ 4. Completion (FieldCompleter)       │
│ For each field:                      │
│  a. Read RAW_VALUE_SLOT +            │
│     ACCESS_CHECK_SLOT                │
│  b. If raw errored → use raw value   │
│     (don't wait for checker)         │
│  c. If checker errored → surface err │
│  d. If both succeed → use raw value  │
└──────────────────────────────────────┘
```

## Bootstrap and Wiring

### Registration

Engineers implement the `CheckerExecutorFactory` SPI to define their own access check rules. The factory is the point where schema-level annotations (e.g., custom directives) are translated into `CheckerExecutor` instances. For each field coordinate or type, the factory reads the relevant schema directive arguments, applies its own business logic to determine which authorization backend to call, and returns a configured `CheckerExecutor`—or `null` to opt out of checking for that field. For example, different directive arguments can produce different checker implementations.

At tenant loading, `DispatcherRegistryFactory` iterates all object types in the schema. For each type and field, it calls `CheckerExecutorFactory.checkerExecutorForField()` and `checkerExecutorForType()`. Non-null results are wrapped in `InstrumentedCheckerDispatcher(CheckerDispatcherImpl(...))` and stored in the `DispatcherRegistry`.

`ExecutorValidator` validates checker RSS during bootstrap: selections must be schematically valid, properly typed, and acyclic.

**Key files:**

- `engine/runtime/.../tenantloading/DispatcherRegistryFactory.kt`
- `engine/runtime/.../tenantloading/ExecutorValidator.kt`
- `engine/runtime/.../tenantloading/CheckerSelectionSetsAreProperlyTyped.kt`

### Engine Assembly

`EngineImpl` wires everything together:

- Creates `AccessCheckRunner(coroutineInterop)`
- Creates `FieldResolver(accessCheckRunner)`
- Creates `FieldCompleter(exceptionHandler)`
- Passes all to `ViaductExecutionStrategy.Factory`

**Key file:** `engine/wiring/.../EngineImpl.kt`

### Service-Level Configuration

`StandardViaduct.Builder` provides the entry points for configuring checkers:

- `withCheckerExecutorFactory()` — static factory
- `withCheckerExecutorFactoryCreator()` — schema-aware factory (deferred creation)

**Key files:**

- `service/runtime/.../StandardViaduct.kt`
- `service/runtime/.../StandardViaductModule.kt`

## Execution Details

### QueryPlan

A `QueryPlan` is an intermediate representation of a GraphQL selection set. It models Viaduct-specific concepts—required selection sets, variables, constraints, and execution conditions—that have no direct representation in the GraphQL AST. The key benefit is that building a plan is expensive (it traverses the schema, resolves field types, looks up checker and resolver RSS from registries, and constructs the full `childPlans` tree), so the plan is built once and cached. Subsequent requests for the same query text reuse the cached plan without any schema or registry lookups.

`QueryPlanFactory.Cached` wraps the builder with a Caffeine async cache (capacity: 10,000 entries). The cache key is `CacheKey(documentText, documentKey, schemaHashCode)`.

**How it's built:** When `QueryPlanFactory.Default.build()` processes a document, `QueryPlanBuilder.processField()` is called for each field in the selection set. For each field, it calls `registry.getFieldCheckerRequiredSelectionSets()` to retrieve the checker's RSS and turns each one into a child `QueryPlan` attached to the field's `childPlans`. Similarly, `buildFieldTypeChildPlans()` calls `registry.getTypeCheckerRequiredSelectionSets()` for each possible concrete type, storing the resulting plans lazily in `fieldTypeChildPlans` (since polymorphic fields typically resolve to just one concrete type at runtime—the other lazies are never forced).

**Cycle detection and checker child plans:** During cycle detection, checker RSS fields are treated as not access-checked—they depend only on `RAW_VALUE_SLOT`—which ensures that checker execution cannot deadlock waiting for its own results. However, when building query plans, checker child plans are still constructed for checker RSS fields. This is because the raw and checker slots must be written at the same time (fields are memoized via OER), and a checker RSS field may also appear in the client query or a field resolver RSS, where the `ACCESS_CHECK_SLOT` is needed.

**Key files:**

- `engine/runtime/.../execution/QueryPlanFactory.kt` — plan building and caching
- `engine/runtime/.../execution/QueryPlan.kt` — plan data structure

### AccessCheckRunner

The central orchestrator. Three public methods:

- **`fieldCheck()`** — Looks up the field's `CheckerDispatcher` from the `DispatcherRegistry`, resolves RSS variables, builds a `CheckerProxyEngineObjectData`, and calls `dispatcher.execute()`.
- **`typeCheck()`** — Same flow for type-level checks. Also pre-fetches child query plans for the type.
- **`combineWithTypeCheck()`** — Waits for the field checker and (if applicable) type checker, then combines their results using `CheckerResult.combine()`.

Both `fieldCheck()` and `typeCheck()` return `Value<out CheckerResult?>`. A `null` value means no checker exists for that field/type; non-null means a check was executed.

**Key file:** `engine/runtime/.../execution/AccessCheckRunner.kt`

### Field Resolution Flow

In `FieldResolver.fetchField()`, the per-field execution proceeds as:

1. **Launch child plans** — `resolveField()` launches the field's `childPlans` (including checker RSS plans) so their data starts resolving.
2. **Start field check** — `accessCheckRunner.fieldCheck()` begins the field-level access check.
3. **Execute data fetcher** — The data fetcher executes the field resolver. Execution depends on operation type:
   - **Query fields** (`executeCheckerSequentially = false`): data fetcher runs immediately, in parallel with the field check.
   - **Mutation/Subscription top-level fields** (`executeCheckerSequentially = true`): waits for `fieldCheckerResultValue` to complete. If the check failed, the data fetcher is **not executed**.
4. **Combine checks** — `accessCheckRunner.combineWithTypeCheck()` takes the field checker result, waits for the type checker (if any), and produces a single `combinedCheckerResult`.
5. **Store in OER** — Both the raw `result` and `combinedCheckerResult` are stored in the parent OER via `setRawValue()` and `setCheckerValue()`.

**Key file:** `engine/runtime/.../execution/FieldResolver.kt`

### The Multi-Slot OER Pattern

Each field in an `ObjectEngineResultImpl` has two slots:

| Slot | Contains | Set by |
|------|----------|--------|
| `RAW_VALUE_SLOT` | Resolver result (`FieldResolutionResult`) | `FieldResolver` |
| `ACCESS_CHECK_SLOT` | Combined checker result (`CheckerResult`) | `FieldResolver` (from `combineWithTypeCheck`) |

For client query fields, `FieldCompleter.combineValues()` reads both slots during completion and determines the final value:

1. If raw value errored → use raw value (don't wait for checker)
2. If checker errored → surface checker error
3. If both succeed → use raw value

For field resolver RSS selections, slot access happens in `ProxyEngineObjectData` (see [CheckerProxyEngineObjectData](#checkerproxyengineobjectdata) below).

**Key files:**

- `engine/runtime/.../ObjectEngineResultImpl.kt` — slot constants, `setCheckerValue()` extension
- `engine/runtime/.../execution/FieldCompleter.kt` — `combineValues()` method

### CheckerProxyEngineObjectData

When a checker declares RSS, the engine resolves that data and provides it as `EngineObjectData`. `CheckerProxyEngineObjectData` wraps the parent OER but overrides `fetchCheckedValue()` to read from `RAW_VALUE_SLOT` only and not `ACCESS_CHECK_SLOT`. This prevents a circular dependency where a checker's RSS includes a field that itself has a checker.

**Key file:** `engine/runtime/.../CheckerProxyEngineObjectData.kt`

## Error Types

- `ViaductErrorType.FailedToPerformPolicyCheck` — Fatal. The check could not be executed (e.g., auth service unavailable).
- `ViaductErrorType.PermissionDenied` — Non-fatal. The check executed but access was denied.

## Observability

Checker execution is instrumented via `ViaductResolverInstrumentation.instrumentAccessChecker()`. The `InstrumentedCheckerExecutor` wraps the real executor, fires instrumentation callbacks, and wraps the `EngineObjectData` with `InstrumentedEngineObjectData` for observability.

**Key files:**

- `engine/api/.../instrumentation/resolver/ViaductResolverInstrumentation.kt`
- `engine/runtime/.../instrumentation/resolver/InstrumentedCheckerExecutor.kt`
- `engine/runtime/.../instrumentation/resolver/InstrumentedCheckerDispatcher.kt`

## Testing

- `AccessCheckExecutionTest` — integration test for the full access check pipeline
- `AccessCheckRunnerTest` — unit tests for `AccessCheckRunner`
- `FieldPolicyCheckTest` — policy check feature test
- `PolicyCheckFeatureAppTest` (in `tenant/`) — end-to-end feature test with custom `@policyCheck` directive

## References

- [`context-flow.md`](../engine/runtime/impldocs/context-flow.md) — Execution context architecture
