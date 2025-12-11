package com.example.viadapp.injector

import viaduct.tenant.runtime.bootstrap.TenantResolverClassFinder
import viaduct.tenant.runtime.bootstrap.TenantResolverClassFinderFactory

/**
 * A [TenantResolverClassFinderFactory] that creates finders using explicitly
 * registered resolver classes from Koin, rather than classpath scanning.
 *
 * @param resolverClasses The set of resolver classes registered in Koin
 */
class KoinTenantResolverClassFinderFactory(
    private val resolverClasses: Set<Class<*>>
) : TenantResolverClassFinderFactory {

    override fun create(packageName: String): TenantResolverClassFinder {
        // Filter resolvers to those in the requested package
        val filteredClasses = resolverClasses.filter {
            it.name.startsWith(packageName)
        }.toSet()

        return KoinTenantResolverClassFinder(filteredClasses)
    }
}
