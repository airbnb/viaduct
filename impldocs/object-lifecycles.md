# Object Lifecycles In Viaduct

Viaduct manages many objects with different lifetimes. Understanding these lifecycles — and which one a given object belongs to — is important for writing correct framework code. The table below summarizes each lifecycle, from the broadest (process-wide singletons) to the narrowest (a single resolver invocation).  See also [context-flow.md](../core/engine/runtime/impldocs/context-flow.md) for details and examples from the engine runtime.

## Lifecycles

| Lifecycle                  | Examples                                 | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| -------------------------- | ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Process                    | `EngineConfiguration`                    | Same as "global" or "singleton" injection scope, these are objects created once when the Viaduct process is started and live for the entire lifetime of that process.                                                                                                                                                                                                                                                                                                                                                              |
| Deployment                 | `Viaduct` and `EngineRegistry` instances | Viaduct aspires to support live deployment of changes to the application code hosted in Viaduct. Each deployment defines a lifecycle of objects whose lifetimes are tied to the lifespan of that deployment.                                                                                                                                                                                                                                                                                                                       |
| Request                    | `DataLoader`                             | Same as "request" injection scope, these are objects tied to the lifetime of a top-level Viaduct request. While it's up to Service Engineers to tie this Viaduct concept to their serving infrastructure, typically this lifecycle is tied to an externally-initiated HTTP request. The `requestContext` object Service Engineers pass to `ExecutionInput` is meant to have request lifecycle.                                                                                                                                     |
| Operation                  | `ExecutionHandle`                        | Viaduct supports the invocation of "subqueries" by calling `ctx.query` (and similarly for submutations). These invocations establish a nested hierarchy of sub-operation-tied lifecycles. While this is mostly an internal engine concept, certain behaviors of query-fragments and subqueries can differ, so this is a concept not entirely hidden from Tenant Developers.                                                                                                                                                        |
| Internal engine lifecycles | `FieldExecutionScope`                    | During the execution of an operation, the engine internally has additional lifecycles guiding the structure of its implementation. These are implementation details that should be able to change over time, so details of these should not leak outside the engine runtime. That said, engine implementors should maintain the same conventions we've established across all of Viaduct when defining these internal lifecycles. See [context-flow.md](../core/engine/runtime/impldocs/context-flow.md) for details and examples. |
| Executor                   | `ResolverBase`                           | The interaction model between the framework and Tenant code is one of simple function invocation: the framework calls a function defined by the tenant, which returns results to the framework. The span of these invocations defines the `Executor` lifecycle.                                                                                                                                                                                                                                                                    |

## Notes

- As mentioned, the Deployment lifecycle at this point is aspirational: we're slowly decoupling the Deployment lifecycle from the Process lifecycle, which is easy to default to (eg, by using singleton objects).
- We have many class whose names end with `Context`.  Another aspiration is to tie these objects to lifecycles, i.e., every class whose name ends with `Context` should be tied to a well-defined context (even if that context is "engine internal").  In addition, the direct state of a context object itself (ie, its properties) should be immutable, and any object reachable from it that isn't immutable should be mutable for a well-understood reason (e.g., in the case of data loaders, they are mutable exactly because they are intended to accumulate, cache, and batch resolver invocations across an entire Request).
- **Error attribution and the Executor lifecycle:** The `handleTenantErrorsSuspend()` / `handleFrameworkErrorsSuspend()` pattern creates an error-attribution boundary roughly aligned with the Executor lifecycle. However, within a single executor invocation, control can pass back and forth between framework code and tenant code (e.g., the framework creates arguments, calls tenant code, then the framework unwraps results). The `@InFrameworkCode` / `@InTenantCode` annotations (documented in [`shared/apiannotations/README.md`](../core/shared/apiannotations/README.md)) exist precisely because the boundary isn't a clean single crossing — they mark functions whose attribution context differs from their module's default. See also [`executor-error-boundaries.md`](../core/shared/errors/impldocs/executor-error-boundaries.md).

## Appendix A: Additional Examples

**Process lifecycle:**

- `SchemaFactory` — singleton in `StandardViaductModule`, parses SDL into `ViaductSchema` instances
- `ExecutableFragmentParser` — singleton, shared across all deployments
- `MeterRegistry` — singleton, metrics registry
- `EngineConfiguration` — bound as instance at process level, carries `FlagManager`, `CoroutineInterop`, `DataFetcherExceptionHandler`, etc.
- `StandardViaduct.Factory` — singleton, the factory that creates per-deployment `StandardViaduct` instances

**Deployment lifecycle:**

- `DispatcherRegistry` — the compiled map of field/node resolver dispatchers and checker dispatchers, rebuilt on each deployment via `DispatcherRegistryFactory`
- `ViaductSchema` — per-schema, built during deployment (can be reused across hot-deploys via `createWithReusedSchemas()`)
- `EngineFactory` — schema-scoped singleton in `SchemaScopedModule`
- `CheckerExecutorFactory` — schema-scoped singleton, creates access checker executors
- `CachingPreparsedDocumentProvider` — query parse cache, has Deployment lifecycle but is mutated on a per-request basis.
- Tenant module bootstrappers — generated registry-backed bootstrappers that create resolver executors, rebuilt on hot-deploy

**Request lifecycle:**

- `EngineExecutionContext` (the full object) — created once per request in `EngineImpl.mkEngineExecutionContext()`
- `FieldDataLoader` / `NodeDataLoader` — one per field-coordinate (or node-type) per request, held in `ConcurrentHashMap` on the EEC
- `ExecutionParameters.Constants` — immutable request-wide state (root OER, coroutine scope factory, `CollectCache`), shared by all `ExecutionParameters` instances within a request
- `SupervisorJob` — the request-scoped coroutine root created in `withRequestSupervisor()`
- `ErrorAccumulator` (root level) — collects GraphQL errors across the request

**Operation lifecycle:**

- `QueryPlan` — built per operation from the parsed document and schema
- `CoercedVariables` — per-operation; notably, subqueries do NOT inherit parent variables (see `subquery-execution.md`)
- `EngineSelectionSet` — per subquery invocation, carries its own variables and fragment definitions

**Internal engine lifecycles:**

- `ViaductDataFetchingEnvironment` — per-field, bridges graphql-java's DFE with Viaduct's EEC
- `ObjectEngineResult` (OER) — per resolved object in the response tree, holds memoized field values in `Cell` slots
- `FieldExecutionScope` — per execution position, carries context-sensitive fragments/variables (root operation uses client fragments; child plans use their own)
- `ExecutionObservabilityContext` — per-field, added to local context for instrumentation/tracing
- `CompositeLocalContext` — per execution position, the graphql-java local context carrying Viaduct's layered state

**Executor lifecycle:**

- `FieldResolverExecutor.batchResolve()` invocation scope — the span of a single batch call, including argument creation and result unwrapping
- `CheckerExecutor.execute()` invocation scope — the span of a single access check
- `VariablesProviderExecutor.resolve()` — fresh `VariablesProvider` instance per call via `Provider.get()`
- Error boundary wrappers (`handleTenantErrorsSuspend` / `handleFrameworkErrorsSuspend`) — create an attribution boundary at exactly this lifecycle level
