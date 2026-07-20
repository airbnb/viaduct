package com.example.starwars.service.viaduct

import io.micronaut.context.BeanContext
import jakarta.inject.Singleton
import javax.inject.Provider
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory

/**
 * Micronaut-backed [SharedTenantModuleInjectorFactory] for the Starwars demo.
 *
 * Every tenant resolver class is itself a Micronaut bean (`@Prototype`, so the [BeanContext]
 * builds a fresh instance per call), so [BeanContext.getBean] alone can construct any of them.
 * Per-tenant bindings, like the `ExternalDataClient` each of `FilmDataSourceResolver` and
 * `PlanetDataSourceResolver` requires, are declared as `@Named`-qualified constructor parameters
 * and resolved against `@Named`-qualified implementations (`FilmArchiveClient`,
 * `UniverseCatalogClient`) registered directly in the shared [BeanContext]. No tenant-aware
 * lookup logic is needed here: every tenant gets the same [BeanContext]-backed injector.
 */
@Singleton
class MicronautTenantModuleInjectorFactory(
    beanContext: BeanContext,
) : SharedTenantModuleInjectorFactory(
        object : CodeInjector {
            override fun <T> getProvider(clazz: Class<T>): Provider<T> = Provider { beanContext.getBean(clazz) }
        }
    )
