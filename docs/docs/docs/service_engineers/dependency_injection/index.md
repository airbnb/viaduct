---
title: Dependency Injection
description: Wiring a DI framework into Viaduct so resolver classes can declare constructor dependencies.
---

[Resolvers](../../developers/index.md) in Viaduct are methods on _resolver container classes._ An instance of a resolver's container class is created _each time the resolver is invoked._ By default, Viaduct uses reflection to instantiate these classes, but Viaduct supports and encourages the use of a Dependency Injection (DI) framework to provide dependencies to resolvers in a type-safe, traceable manner.

To incorporate your preferred DI framework into Viaduct, you need to provide implementations of {{ kdoc("viaduct.service.api.spi.CodeInjector") }}:

```kotlin
interface CodeInjector {
    fun <T> getProvider(clazz: Class<T>): Provider<T>
}
```

Viaduct calls `getProvider(resolverClass).get()` before each resolver invocation. Implementations must be thread-safe.

### `TenantModuleInjectorFactory`

```kotlin
interface TenantModuleInjectorFactory {
    suspend fun bootstrap(tenantName: String, tenantBootstrapClass: Class<*>?): CodeInjector
    suspend fun onBootstrapComplete()
}
```

Your implementations of this interface are given to {{ kdoc("viaduct.service.ViaductBuilder") }} (see [Server Integration](../server_integration/index.md)) by giving it an instance of {{ kdoc("viaduct.service.ViaductBuilder.withTenantModuleInjectorFactory") }}:

```kotlin
val viaduct: Viaduct = ViaductBuilder()
    .withTenantModuleInjectorFactory(tenantModuleInjectorFactory)
    ...
    .build()
```

({{ kdoc("viaduct.service.BasicViaductFactory.create") }} also lets you set an injector factory).

### Implementing from Java

`TenantModuleInjectorFactory`'s hooks are `suspend` functions. Java tenants implement the SPI by extending {{ kdoc("viaduct.service.api.spi.JavaTenantModuleInjectorFactory") }} and overriding `bootstrapBlocking` (and, if needed, `onBootstrapCompleteBlocking`):

```java
public final class MyInjectorFactory extends JavaTenantModuleInjectorFactory {
    @Override
    public CodeInjector bootstrapBlocking(String tenantName, Class<?> tenantBootstrapClass) {
        return ...; // build this tenant's injector (may block)
    }
}
```

The base class runs your method on an `Executor` — defaulting to `ForkJoinPool.commonPool()`, or one you pass to the constructor — so blocking work such as file IO never blocks the framework's coroutine thread.

## SharedTenantModuleInjectorFactory

{{ kdoc("viaduct.service.api.spi.SharedTenantModuleInjectorFactory") }} is a pre-defined implementation of `TenantModuleInjectorFactory` that is adequate for most Viaduct applications.  (It's "shared" because it lets you define a single injector that's shared across all tenant modules.)  Its constructor takes an instance of {{ kdoc("viaduct.service.api.spi.CodeInjector") }}

{{ codetag("core/service/api/src/main/kotlin/viaduct/service/api/spi/TenantModuleInjectorFactory.kt", "shared_bootstrapper_def", lang="kotlin") }}

This class can be used to configure Viaduct to use an injector as follows:

```kotlin
val theInjector = ...configure the shared injector
val codeInjector = object : CodeInjector {
    override fun <T> getProvider(clazz: Class<T>): Provider<T> =
        theInjector.getProvider(clazz)
}
val viaduct: Viaduct = ViaductBuilder()
    .withTenantModuleInjectorFactory(SharedTenantModuleInjectorFactory(codeInjector))
    ...
    .build()
```

At startup time, Viaduct will call `getProvider(resolverClass)` once per resolver-containing class to obtain a provider for that class. When executing operations, it will use that provider to instantiate resolver-containing classes each time a resolver needs to be invoked.

Request-scoped framework beans work naturally with this model: the transport entry point populates request-scoped data, and resolvers declare those beans as constructor dependencies. See [Server Integration](../server_integration/index.md#request-scoped-data) for how request data enters Viaduct execution.

### Best Practices with SharedTenantModuleInjectorFactory

Viaduct encourages organizations to decompose their overall application into [multiple tenant modules](../multi_tenancy/index.md). A shared injector is practical for multi-tenant applications, but it does require some management to avoid conflicts. A simple and effective form of management is code review: place the DI configuration in a source location where the Viaduct Service Engineering team is a mandatory reviewer of changes. The SE team can establish broad guidelines regarding how to introduce new bindings, including guidance for avoiding conflicts, and then any specific changes can be reviewed against these guidelines.

For applications with many tenants, mechanisms in the DI framework can be used to help enforce modular configuration of the injector. First, most DI frameworks have mechanisms for decomposing the full set of bindings used to configure an injector in some modular fashion, rather than placing them all in one monolithic configuration. Guice, for example, has `AbstractModule`s; Spring has `@Configuration` classes; Dagger has `@Module`s. Each team can own its own such module, and the Service Engineering team can establish an idiom that allows teams to inject their module into the overall injection configuration. Even where each team defines bindings in its own module, combining them into a single injector can still result in conflicts. To address this problem, Service Engineers can establish conventions for using "qualified" bindings to provide isolation (e.g., `@Named` in Guice and Dagger, `@Qualifier` in Guice).

Over time, the bindings of even a medium-sized application can become a source of technical debt. Service Engineers should periodically audit the full set of bindings in their application, consolidating duplicative, qualified bindings that appear in multiple tenants, and eliminating dead bindings.

## Per-Tenant Injectors

To achieve a higher level of encapsulation than can be achieved with `SharedTenantModuleInjectorFactory`, Viaduct supports the use of a different {{ kdoc("viaduct.service.api.spi.CodeInjector") }} per tenant. Custom implementations of {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory") }} are the mechanism for creating per-module injectors:

{{ codetag("core/service/api/src/main/kotlin/viaduct/service/api/spi/TenantModuleInjectorFactory.kt", "module_bootstrapper_def", lang="kotlin", strip_comments=True) }}

At startup time Viaduct will call the {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.bootstrap") }} method once for every tenant module in the application. `tenantName` will be set to the name of the tenant module, and `tenantBootstrapClass` will be set to the class in that module (if there is one) that is annotated with {{ kdoc("service.api.spi.TenantBootstrapper") }}.

It is up to the Service Engineer to define the expectation for this bootstrap class. Typically this class is a factory that will return a set of injector bindings for the tenant. When using Guice, for example, this class might return an `AbstractModule` that can be used to create an injector for the tenant. {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.bootstrap") }} would use this module to create an injector, which it would return.

Most DI frameworks have a mechanism for creating child injectors. A common design would be to create a parent injector for bindings that are shared across all tenants, and use the bindings from {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.bootstrap") }} to create per-module child injectors.

Service Engineers might want to collect bindings from across all tenant modules before creating any injectors. The {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.onBootstrapComplete") }} function supports this pattern. After {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.bootstrap") }} is called for all tenant modules, Viaduct will call {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.onBootstrapComplete") }}. The {{ kdoc("viaduct.service.api.spi.CodeInjector") }} instances returned by {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.bootstrap") }} will _not_ be used until after {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.onBootstrapComplete") }} is called. The intended pattern is for the results of {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.bootstrap") }} to be lazily initialized objects, and for {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory.onBootstrapComplete") }} to initialize them.

See [`starwars`](https://github.com/viaduct-dev/starwars) for an example of using {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory") }} to configure per-tenant injectors.

## Summary

| Scenario | Approach |
|---|---|
| All tenants share the same DI container | {{ kdoc("viaduct.service.api.spi.SharedTenantModuleInjectorFactory") }} |
| Each tenant needs distinct bindings | {{ kdoc("viaduct.service.api.spi.TenantModuleInjectorFactory") }} |
| No DI (tests, simple apps) |  {{ kdoc("viaduct.service.api.spi.NaiveTenantModuleInjectorFactory") }} |

## See also

- [Multi-tenancy](../multi_tenancy/index.md)
