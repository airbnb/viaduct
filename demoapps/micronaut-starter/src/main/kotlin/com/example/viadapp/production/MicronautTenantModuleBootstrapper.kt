package com.example.viadapp.production

import io.micronaut.context.BeanContext
import jakarta.inject.Singleton
import javax.inject.Provider
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleBootstrapper

@Singleton
class MicronautTenantModuleBootstrapper(
    beanContext: BeanContext,
) : SharedTenantModuleBootstrapper(MicronautCodeInjector(beanContext)) {
    private class MicronautCodeInjector(private val beanContext: BeanContext) : CodeInjector {
        override fun <T> getProvider(clazz: Class<T>): Provider<T> =
            Provider {
                beanContext.getBean(clazz)
            }
    }
}
