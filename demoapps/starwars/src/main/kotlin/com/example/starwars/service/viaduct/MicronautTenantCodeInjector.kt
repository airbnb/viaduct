package com.example.starwars.service.viaduct

import io.micronaut.context.BeanContext
import jakarta.inject.Singleton
import javax.inject.Provider
import viaduct.service.api.spi.TenantCodeInjector

@Singleton
class MicronautTenantCodeInjector(
    private val beanContext: BeanContext
) : TenantCodeInjector {
    override fun <T> getProvider(clazz: Class<T>): Provider<T> {
        return Provider {
            beanContext.getBean(clazz)
        }
    }
}
