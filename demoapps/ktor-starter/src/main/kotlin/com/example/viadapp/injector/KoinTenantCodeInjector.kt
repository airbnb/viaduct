package com.example.viadapp.injector

import org.koin.core.Koin
import viaduct.service.api.spi.TenantCodeInjector
import javax.inject.Provider
import kotlin.reflect.KClass

/**
 * A [TenantCodeInjector] implementation that uses Koin for dependency injection.
 *
 * This injector delegates resolver instantiation to Koin, allowing resolvers
 * to have constructor-injected dependencies rather than using KoinComponent.
 *
 * @param koin The Koin instance to use for resolving dependencies
 */
class KoinTenantCodeInjector(private val koin: Koin) : TenantCodeInjector {
    @Suppress("UNCHECKED_CAST")
    override fun <T> getProvider(clazz: Class<T>): Provider<T> {
        val kclass = (clazz as Class<Any>).kotlin
        return Provider {
            koin.get(kclass) as T
        }
    }
}
