---
title: Dependency Injection
description: Wiring a DI framework into Viaduct so resolver classes can declare constructor dependencies.
---

Viaduct instantiates a new resolver class instance for each field invocation. The component responsible for creating those instances is [`CodeInjector`][CodeInjector]. The default implementation, `CodeInjector.Naive`, uses zero-argument constructors. To enable constructor injection, provide a `CodeInjector` backed by your DI container.

## Key interfaces

### `CodeInjector`

```kotlin
interface CodeInjector {
    fun <T> getProvider(clazz: Class<T>): Provider<T>
}
```

Viaduct calls `getProvider(resolverClass).get()` before each resolver invocation. Implementations must be thread-safe.

### `TenantModuleBootstrapper`

```kotlin
interface TenantModuleBootstrapper {
    suspend fun bootstrap(tenantName: String, tenantBootstrapClass: Class<*>?): CodeInjector
    suspend fun finalize() = Unit
}
```

Called once per tenant module at startup. The returned `CodeInjector` is used for all resolver classes in that tenant. `finalize` is called once after all `bootstrap` calls complete, before the service starts handling requests.

`tenantBootstrapClass` is the class annotated with `@TenantBootstrapper` in that tenant's config file, or `null` if the tenant has none.

## Shared injector (most applications)

When all tenants share the same DI container, extend `SharedTenantModuleBootstrapper`:

```kotlin
class MicronautTenantModuleBootstrapper(
    beanContext: BeanContext,
) : SharedTenantModuleBootstrapper(MicronautCodeInjector(beanContext)) {

    private class MicronautCodeInjector(
        private val beanContext: BeanContext,
    ) : CodeInjector {
        override fun <T> getProvider(clazz: Class<T>): Provider<T> =
            Provider { beanContext.getBean(clazz) }
    }
}
```

Pass it to `BasicViaductFactory.create` or `ViaductBuilder`:

=== "BasicViaductFactory"

    ```kotlin
    val viaduct: Viaduct = BasicViaductFactory.create(
        tenantModuleBootstrapper = tenantModuleBootstrapper,
    )
    ```

=== "ViaductBuilder"

    ```kotlin
    val viaduct: Viaduct = ViaductBuilder()
        .withTenantModuleBootstrapper(tenantModuleBootstrapper)
        .withMeterRegistry(meterRegistry)
        .build()
    ```

## Complete Micronaut example

### 1. Bootstrapper

```kotlin title="production/MicronautTenantModuleBootstrapper.kt"
import io.micronaut.context.BeanContext
import jakarta.inject.Singleton
import javax.inject.Provider
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleBootstrapper

@Singleton
class MicronautTenantModuleBootstrapper(
    beanContext: BeanContext,
) : SharedTenantModuleBootstrapper(MicronautCodeInjector(beanContext)) {

    private class MicronautCodeInjector(
        private val beanContext: BeanContext,
    ) : CodeInjector {
        override fun <T> getProvider(clazz: Class<T>): Provider<T> =
            Provider { beanContext.getBean(clazz) }
    }
}
```

### 2. Viaduct factory

```kotlin title="production/ViaductConfiguration.kt"
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import viaduct.service.BasicViaductFactory
import viaduct.service.api.Viaduct

@Factory
class ViaductConfiguration(
    private val tenantModuleBootstrapper: MicronautTenantModuleBootstrapper,
) {
    @Bean
    fun providesViaduct(): Viaduct =
        BasicViaductFactory.create(
            tenantModuleBootstrapper = tenantModuleBootstrapper,
        )
}
```

### 3. Resolver with injected dependencies

```kotlin
@Resolver
class CharacterResolver(
    private val characterService: CharacterService,
) : CharacterQueryResolverBase() {
    override suspend fun resolve(ctx: QueryExecutionContext): Character? =
        characterService.findById(ctx.arguments.id)
}
```

## Per-tenant injectors

To give each tenant its own injector configuration, implement `TenantModuleBootstrapper` directly. Tenant developers annotate a class with `@TenantBootstrapper`; that class is passed to `bootstrap` so you can use it to configure the injector:

```kotlin
class GuiceTenantModuleBootstrapper : TenantModuleBootstrapper {
    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector {
        val modules = buildList {
            add(CoreModule())
            if (tenantBootstrapClass != null) {
                add(tenantBootstrapClass.getDeclaredConstructor().newInstance() as Module)
            }
        }
        val injector = Guice.createInjector(modules)
        return CodeInjector { clazz -> Provider { injector.getInstance(clazz) } }
    }
}
```

A tenant opts in by annotating its module class with `@TenantBootstrapper`:

```kotlin
@TenantBootstrapper
class MyTenantModule : AbstractModule() {
    override fun configure() {
        bind(MyRepository::class.java).to(MyRepositoryImpl::class.java)
    }
}
```

## Development server

The `serve` task requires a `@ViaductServerConfiguration` class to enable injection during development. Start a lightweight instance of your DI container there:

```kotlin title="dev/MicronautViaductFactory.kt"
import io.micronaut.context.ApplicationContext
import viaduct.serve.ViaductFactory
import viaduct.serve.ViaductServerConfiguration
import viaduct.service.api.Viaduct

@ViaductServerConfiguration
class MicronautViaductFactory : ViaductFactory {
    override fun mkViaduct(): Viaduct {
        val context = ApplicationContext.builder()
            .packages(
                "com.example.app.production",
                "com.example.app",
            )
            .start()
        return context.getBean(Viaduct::class.java)
    }
}
```

## Summary

| Scenario | Approach |
|---|---|
| All tenants share the same DI container | `SharedTenantModuleBootstrapper` |
| Each tenant needs distinct bindings | `TenantModuleBootstrapper` + `@TenantBootstrapper` |
| No dependencies (tests, simple apps) | `NaiveTenantModuleBootstrapper` (default) |

## See also

- [Multi-tenancy](../multi_tenancy/index.md)
