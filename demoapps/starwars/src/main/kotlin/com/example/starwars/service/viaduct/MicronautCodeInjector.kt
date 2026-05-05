package com.example.starwars.service.viaduct

import io.micronaut.context.BeanContext
import jakarta.inject.Singleton
import javax.inject.Provider
import viaduct.service.api.spi.CodeInjector

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
