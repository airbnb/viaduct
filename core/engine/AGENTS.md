This directory contains the core GraphQL execution algorithm implemented by Viaduct.

There are two interfaces to the engine: the top-level interface used to initiate an operation and receive its results, and an intra-operation interface used during the execution of an operation to interact with resolvers.

This directory contains three subprojects: `api/` defines the top-level and intra-operation interfaces, `runtime/` implements them, and `wiring/` constructs the engine. The top-level API is [`viaduct.engine.api.Engine`](api/src/main/kotlin/viaduct/engine/api/Engine.kt), instantiated by [`EngineFactory`](wiring/src/main/kotlin/viaduct/engine/EngineFactory.kt).

Most of `engine/api` pertains to the intra-operation API used by the engine to invoke resolvers.  At the core of this API are the _executor_ Kotlin interfaces, for example [`viaduct.engine.api.spi.FieldResolverExecutor`](api/src/main/kotlin/viaduct/engine/api/spi/FieldResolverExecutor.kt).  The Tenant API (found in `../tenant`) is responsible for producing an implementation of this interface for each field resolver written by application developers.  When it's time to invoke a resolver, the engine calls the `FieldResolverExecutor.batchResolve` function to do so.

## File-based tenant loading

The engine owns bootstrap orchestration in `runtime/.../tenantloading/`. Service configuration and the `TenantModuleInjectorFactory` lifecycle are described in [service guidance](../service/AGENTS.md).

1. Build tooling emits `ExecutionRegistryConfigFile` JSON under `META-INF/viaduct/modules/`. The [shared bootstrap model](../shared/bootstrap/README.md) defines the format; the [KSP pipeline](../../impldocs/execution-registry-ksp-pipeline.md) explains Kotlin generation. Resolver discovery happens at build time.
2. The service builder uses [`ExecutionRegistryConfigSourceCollector.fromResources`](runtime/src/main/kotlin/viaduct/engine/runtime/tenantloading/ExecutionRegistryConfigSourceCollector.kt) to collect resource-backed `ModuleConfigSource`s, or accepts an explicit source list. This scans JSON resources, not resolver classes. [`ModuleBootstrapConfiguration`](../service/runtime/src/main/kotlin/viaduct/service/runtime/ModuleBootstrapConfiguration.kt) carries the inputs into schema scope.
3. [`ModuleConfigBootstrapper`](runtime/src/main/kotlin/viaduct/engine/runtime/tenantloading/ModuleConfigBootstrapper.kt) checks source uniqueness, parses the configs, groups them by tenant for injector creation, and completes the injector lifecycle. It then loads the `executorFactory` class named by each config and constructs one factory per source.
4. [`ModuleResolvers`](runtime/src/main/kotlin/viaduct/engine/runtime/tenantloading/ModuleResolvers.kt) filters entries against the schema and asks each factory for field and node executors. [`StandardDispatcherRegistryFactory`](runtime/src/main/kotlin/viaduct/engine/runtime/tenantloading/DispatcherRegistryFactory.kt) assembles dispatchers, applies resolver proxies and instrumentation, registers access checkers, and validates the resulting registry.

File-based loading describes the metadata contract, not a requirement that every source be a disk file. Tests and schema-derived built-ins can supply serialized configs through `InputStreamSource`. Runtime still loads the classes named in those configs; do not add a resolver-class scanning fallback or restore `TenantAPIBootstrapper`.

## Bootstrap invariants

- Config identity is `<tenantName, apiName>`; `executorFactory` and resource paths are not keys. Duplicate keys fail in the collector and again in `ModuleConfigBootstrapper`, including for caller-supplied lists. Injector scope is coarser: one injector per tenant name, shared across that tenant's API configs. All non-null `bootstrapClass` declarations for a tenant must agree.
- Executor factories must have a `(CodeInjector, ExecutionRegistryConfigFile)` constructor. When `grtPackagePrefix` is supplied, the bootstrapper instead requires `(CodeInjector, String, ExecutionRegistryConfigFile)`.
- [`SchemaScopedModule`](../service/runtime/src/main/kotlin/viaduct/service/runtime/SchemaScopedModule.kt) supplies generated configs for `Query.node`, `Query.nodes`, and `@namespaceType`. They bootstrap in a separate pass with `NaiveTenantModuleInjectorFactory` and are appended after tenant contributions. Later registrations win for resolver coordinates across modules, so built-ins take precedence there. This differs from duplicate config keys, which are errors.
- Entries absent from the current schema are filtered out. Duplicate coordinates within a module raise `TenantModuleException`; the registry factory logs and skips that module. Executor validation runs after assembly; the service also enables strict missing-resolver validation by default.

For config identity, uniqueness, and replacement during registry rebuilds, use the [bootstrap identity document](../../impldocs/execution-registry-bootstrap.md). Keep that protocol documented there rather than duplicating it here.

## Tests to consult

- [ExecutionRegistryConfigSourceCollectorTest](runtime/src/test/kotlin/viaduct/engine/runtime/tenantloading/ExecutionRegistryConfigSourceCollectorTest.kt) — resource discovery and source identity.
- [ModuleConfigBootstrapperTest](runtime/src/test/kotlin/viaduct/engine/runtime/tenantloading/ModuleConfigBootstrapperTest.kt) — one injector per tenant, duplicate source rejection, and conflicting bootstrap classes.
- [ExecutionRegistryBootstrapperCycleTest](runtime/src/test/kotlin/viaduct/engine/runtime/tenantloading/ExecutionRegistryBootstrapperCycleTest.kt) — a supplied config and mock injector exercising required-selection cycle validation through the file-based path.
- [FileBasedBootstrapContractTest](../tenant/runtime/src/testFixtures/kotlin/viaduct/tenant/runtime/execution/filebased/FileBasedBootstrapContractTest.kt) — generated classpath metadata through actual field, node, and variable-provider execution.

## Implementation Documentation

- [`runtime/impldocs/context-flow.md`](runtime/impldocs/context-flow.md) — Documents the flow of execution context through the engine: the Execution Entry Context (EEC), Local Context, Data Fetching Environment (DFE), and `ExecutionParameters`.

## Module Descriptions

- [`api/module.md`](api/module.md) — Package-level descriptions for the engine API packages.
