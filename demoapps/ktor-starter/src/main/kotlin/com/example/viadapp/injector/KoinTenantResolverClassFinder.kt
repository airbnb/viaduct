package com.example.viadapp.injector

import kotlin.reflect.KClass
import viaduct.api.internal.ObjectBase
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.bootstrap.TenantResolverClassFinder

/**
 * A [TenantResolverClassFinder] that gets resolver classes from an explicitly
 * registered set, rather than scanning the classpath.
 *
 * This allows Koin to be the source of truth for resolver registration.
 * Resolvers must be explicitly registered in the [resolverClasses] set.
 *
 * @param resolverClasses The set of resolver classes to use
 * @param grtPackagePrefix The package prefix for GRT classes (still loaded via classloader)
 */
class KoinTenantResolverClassFinder(
    private val resolverClasses: Set<Class<*>>,
    private val grtPackagePrefix: String = "viaduct.api.grts"
) : TenantResolverClassFinder {

    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader

    override fun resolverClassesInPackage(): Set<Class<*>> = resolverClasses

    override fun nodeResolverForClassesInPackage(): Set<Class<*>> = emptySet()

    override fun getSubTypesOf(typeName: String): Set<Class<*>> = emptySet()

    override fun grtClassForName(typeName: String): KClass<ObjectBase> {
        @Suppress("UNCHECKED_CAST")
        return classLoader.loadClass("$grtPackagePrefix.$typeName").kotlin as KClass<ObjectBase>
    }

    override fun argumentClassForName(typeName: String): KClass<out Arguments> {
        @Suppress("UNCHECKED_CAST")
        return classLoader.loadClass("$grtPackagePrefix.$typeName").kotlin as KClass<out Arguments>
    }
}
