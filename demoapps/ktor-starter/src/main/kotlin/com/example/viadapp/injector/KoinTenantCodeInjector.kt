package com.example.viadapp.injector

import org.koin.core.Koin
import viaduct.service.api.spi.TenantCodeInjector
import javax.inject.Provider

/**
 * A [TenantCodeInjector] implementation that uses Koin for dependency injection.
 *
 * This injector delegates resolver instantiation to Koin, allowing resolvers
 * to have constructor-injected dependencies rather than using KoinComponent.
 *
 * @param koin The Koin instance to use for resolving dependencies
 */
class KoinTenantCodeInjector(private val koin: Koin) : TenantCodeInjector {
    override fun <T> getProvider(clazz: Class<T>): Provider<T> {
        return Provider {
            koin.get(clazz.kotlin)
        }
    }
}
