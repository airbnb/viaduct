package viaduct.tenant.runtime.bootstrap

import kotlin.reflect.KClass
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.ResolverFor
import viaduct.api.types.Arguments
import viaduct.engine.api.TenantModuleMetadata
import viaduct.utils.classgraph.ClassGraphScanner
import viaduct.utils.slf4j.logger

class ViaductTenantResolverClassFinder(
    private val tenantPackage: String,
    private val grtPackagePrefix: String,
    private val withNewScanner: Boolean = false,
    private val metadata: TenantModuleMetadata = TenantModuleMetadata.EMPTY,
) : TenantResolverClassFinder {
    companion object {
        private val log by logger()
    }

    // When withNewScanner = false (default at startup): use optimizedForPackagePrefix which returns
    // the shared INSTANCE scanner for packages within initialized prefixes. This avoids
    // redundant scanning since the global scan already covers these packages.
    //
    // When withNewScanner = true: create a new scanner scoped to this tenant package.
    // A new scanner performs a fresh classpath scan, picking up newly loaded classes
    // (e.g., from HotswapAgent reloading .class files into /srv/classes).
    //
    // Tests use custom packages different from default packages in ClassGraphScanner,
    // so they also get a new scanner (via optimizedForPackagePrefix's fallback).
    //
    // TODO: remove this when we get rid of ClassGraph to discover tenant resolver classes
    private val classGraph = if (withNewScanner) {
        log.info("Creating fresh scanner for tenant package: {}", tenantPackage)
        ClassGraphScanner.forPackagePrefix(tenantPackage)
    } else {
        ClassGraphScanner.optimizedForPackagePrefix(tenantPackage)
    }

    override fun resolverClassesInPackage(): Set<Class<*>> = classGraph.getTypesAnnotatedWith(ResolverFor::class.java, listOf(tenantPackage))

    override fun nodeResolverForClassesInPackage(): Set<Class<*>> = classGraph.getTypesAnnotatedWith(NodeResolverFor::class.java, listOf(tenantPackage))

    override fun <T : Any?> getSubTypesOf(type: Class<T>): Set<Class<out T>> = classGraph.getSubTypesOf(type, listOf(tenantPackage))

    override fun grtClassForName(typeName: String): KClass<ObjectBase> {
        @Suppress("UNCHECKED_CAST")
        return Class.forName("$grtPackagePrefix.$typeName").kotlin as KClass<ObjectBase>
    }

    override fun argumentClassForName(typeName: String): KClass<out Arguments> {
        @Suppress("UNCHECKED_CAST")
        return Class.forName("$grtPackagePrefix.$typeName").kotlin as KClass<out Arguments>
    }

    override fun tenantModuleMetadata(): TenantModuleMetadata = metadata
}
