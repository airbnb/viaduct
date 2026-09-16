This directory is for service engineers integrating Viaduct with web serving, dependency injection, observability, and security. Tenant developers write resolvers using `../tenant/api`.

- `api/` defines [`Viaduct`](api/src/main/kotlin/viaduct/service/api/Viaduct.kt) and service-owned SPIs such as `CodeInjector` and `ErrorReporter`.
- `runtime/` implements the service and owns schema-scoped engine construction.
- `wiring/` exposes [`BasicViaductFactory`](wiring/src/main/kotlin/viaduct/service/BasicViaductFactory.kt) and [`ViaductBuilder`](wiring/src/main/kotlin/viaduct/service/ViaductBuilder.kt).

## Configuring tenant bootstrapping

Use `ViaductBuilder().withTenantModuleInjectorFactory(factory).build()` to load tenant module configs from classpath resources. `BasicViaductFactory.create()` supplies `NaiveTenantModuleInjectorFactory` by default. A bare `ViaductBuilder` does **not** discover tenant configs: without an explicit factory, its tenant source list is empty, though generated built-in resolvers remain enabled.

The [`TenantModuleInjectorFactory`](api/src/main/kotlin/viaduct/service/api/spi/TenantModuleInjectorFactory.kt) SPI supplies dependency injection for modules discovered from generated JSON. It does not discover resolver classes or replace the config producer. Choose:

- `NaiveTenantModuleInjectorFactory` for classes constructible by `CodeInjector.Naive` using zero-argument constructors.
- `SharedTenantModuleInjectorFactory(codeInjector)` when all tenants use the same injector. The [Starwars Micronaut factory](../../demoapps/starwars/src/main/kotlin/com/example/starwars/service/viaduct/MicronautTenantModuleInjectorFactory.kt) is an example.
- A custom `TenantModuleInjectorFactory` for per-tenant bindings or cross-tenant initialization. Java implementations extend `JavaTenantModuleInjectorFactory` and implement `bootstrapBlocking` and, optionally, `onBootstrapCompleteBlocking`.

[`@TenantBootstrapper`](api/src/main/kotlin/viaduct/service/api/spi/TenantBootstrapper.kt) still exists as a **build-time annotation**. The Kotlin and Java processors record the annotated class name in `bootstrapClass` in the module config. The framework loads that class and passes it to the factory; the factory determines its required type and how to use it. The removed `TenantAPIBootstrapper` is not this annotation.

## Injector lifecycle

For each tenant registry build, the engine:

1. Reads the config sources and resolves each tenant's optional bootstrap class.
2. Calls `suspend bootstrap(tenantName, tenantBootstrapClass)` sequentially, once per tenant name. A tenant with multiple API configs shares one returned `CodeInjector`; a tenant without a bootstrap class receives `null`.
3. Calls `suspend onBootstrapComplete()` once after all bootstrap calls succeed, before using any returned injector to construct executor factories. If bootstrapping or this hook fails, executor factory construction does not proceed.

These are registry-build hooks, not request hooks. Config reads and executor factory construction may run concurrently; the factory's `bootstrap` calls do not. Generated built-ins use a separate naive factory, so they do not cause additional calls to the service-owned factory. See [engine guidance](../engine/AGENTS.md) for that assembly flow.

## Tests and diagnosis

- For isolated runtime tests, pair `StandardViaduct.Builder.withTenantModuleInjectorFactory(...)` with `withExecutorRegistryConfigSources(...)`. The latter is a test-only override that replaces resource discovery and is ignored unless a factory is supplied. Its optional `grtPackagePrefix` supports tests that generate GRTs outside the production package.
- [StandardViaductTest](runtime/src/test/kotlin/viaduct/service/runtime/StandardViaductTest.kt) demonstrates supplied config sources, `EngineTestModule.toModuleConfigSource()` with `MockExecutorCodeInjector`, and rebuilding with reused schemas.
- When a resolver is missing at startup, check that the factory is configured and the tenant artifact contains the expected `META-INF/viaduct/modules/*.json` entry. Follow [engine guidance](../engine/AGENTS.md) to inspect config keys and executor entries.

## Module Descriptions

- [`module.md`](module.md) — Package-level descriptions for the Service API and Service Wiring packages.
