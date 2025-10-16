package com.example.starwars.service.viaduct

import javax.inject.Provider
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import viaduct.service.api.spi.TenantCodeInjector

/**
 * The Viaduct [TenantCodeInjector] is responsible for providing in  context [viaduct.api.Resolver] instances that are
 * part of the tenant code.
 *
 * Given a class, the resolver returns the context instance of that class.
 *
 * All bean definitions are injected to the context via [ViaductResolverRegistrar], which scans
 * for all classes annotated with [viaduct.api.Resolver] and registers them as beans
 */
@Service
class SpringTenantCodeInjector(
    private val context: ApplicationContext
) : TenantCodeInjector {
    override fun <T> getProvider(clazz: Class<T>): Provider<T> {
        return Provider {
            context.getBean(clazz)
        }
    }
}
