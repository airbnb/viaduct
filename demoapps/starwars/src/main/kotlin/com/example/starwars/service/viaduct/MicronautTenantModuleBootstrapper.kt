package com.example.starwars.service.viaduct

import io.micronaut.context.BeanContext
import jakarta.inject.Singleton
import javax.inject.Provider
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleBootstrapper

/**
 * Shared Micronaut-backed bootstrapper for the Starwars demo.
 *
 * TODO(https://app.asana.com/1/150975571430/project/1207604899751448/task/1214588083266281?focus=true):
 * demonstrate tenant-specific injector configuration once the dispatcher registry supports
 * distinct per-tenant injectors.
 */
@Singleton
class MicronautTenantModuleBootstrapper(
    beanContext: BeanContext,
) : SharedTenantModuleBootstrapper(MicronautCodeInjector(beanContext)) {
    private class MicronautCodeInjector(private val beanContext: BeanContext) : CodeInjector {
        override fun <T> getProvider(clazz: Class<T>): Provider<T> =
            Provider {
                beanContext.findBean(clazz).orElseGet {
                    clazz.getDeclaredConstructor().newInstance()
                }
            }
    }
}
