package com.example.viadapp.production

import io.micronaut.context.BeanContext
import jakarta.inject.Singleton
import javax.inject.Provider
import viaduct.service.api.spi.CodeInjector

/**
 * Micronaut-based CodeInjector that uses BeanContext for dependency injection.
 *
 * This allows resolvers and tenant modules to have their dependencies
 * automatically injected by Micronaut's DI container.
 *
 * This is used in both production and development modes.
 */
@Singleton
class MicronautCodeInjector(
    private val beanContext: BeanContext
) : CodeInjector {
    override fun <T> getProvider(clazz: Class<T>): Provider<T> {
        return Provider {
            beanContext.getBean(clazz)
        }
    }

    override fun <T> getProvider(
        clazz: Class<T>,
        qualifier: Annotation
    ): Provider<T> {
        throw UnsupportedOperationException("MicronautCodeInjector does not support qualified bindings")
    }
}
