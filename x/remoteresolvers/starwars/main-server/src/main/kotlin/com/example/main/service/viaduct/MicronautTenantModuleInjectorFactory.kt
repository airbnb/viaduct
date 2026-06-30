package com.example.main.service.viaduct

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
                // Mirror the canonical demoapp injector: prefer a managed bean (resolvers with
                // injected dependencies), falling back to reflective no-arg construction for plain
                // resolvers — e.g. computed field resolvers like Character.isAdult — that are not
                // registered as Micronaut beans.
                beanContext.findBean(clazz).orElseGet {
                    clazz.getDeclaredConstructor().newInstance()
                }
            }
    }
}
