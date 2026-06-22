package com.example.rrp.service.viaduct

import io.micronaut.context.BeanContext
import jakarta.inject.Singleton
import javax.inject.Provider
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.SharedTenantModuleInjectorFactory

@Singleton
class MicronautTenantModuleInjectorFactory(
    beanContext: BeanContext,
) : SharedTenantModuleInjectorFactory(MicronautCodeInjector(beanContext)) {
    private class MicronautCodeInjector(private val beanContext: BeanContext) : CodeInjector {
        override fun <T> getProvider(clazz: Class<T>): Provider<T> =
            Provider {
                beanContext.getBean(clazz)
            }
    }
}
