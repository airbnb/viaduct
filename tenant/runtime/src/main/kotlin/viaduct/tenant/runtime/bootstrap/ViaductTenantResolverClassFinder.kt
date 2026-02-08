package viaduct.tenant.runtime.bootstrap

import kotlin.reflect.KClass
import viaduct.api.internal.NodeResolverFor
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.ResolverFor
import viaduct.api.types.Arguments
import viaduct.utils.classgraph.ClassGraphScanner
import viaduct.utils.slf4j.logger

class ViaductTenantResolverClassFinder(
    private val tenantPackage: String,
    private val grtPackagePrefix: String
) : TenantResolverClassFinder {
    companion object {
        private val log by logger()
    }

    // Tests uses custom package that is different from the default packages in ClassGraphScanner.
    // So need to create a new scanner for test purpose.
    private val classGraph = ClassGraphScanner.optimizedForPackagePrefix(tenantPackage)

    override fun resolverClassesInPackage(): Set<Class<*>> = classGraph.getTypesAnnotatedWith(ResolverFor::class.java, listOf(tenantPackage))

    override fun nodeResolverForClassesInPackage(): Set<Class<*>> = classGraph.getTypesAnnotatedWith(NodeResolverFor::class.java, listOf(tenantPackage))

    override fun <T : Any?> getSubTypesOf(type: Class<T>): Set<Class<out T>> = classGraph.getSubTypesOf(type)

    override fun grtClassForName(typeName: String): KClass<ObjectBase> {
        @Suppress("UNCHECKED_CAST")
        return Class.forName("$grtPackagePrefix.$typeName").kotlin as KClass<ObjectBase>
    }

    override fun argumentClassForName(typeName: String): KClass<out Arguments> {
        @Suppress("UNCHECKED_CAST")
        return Class.forName("$grtPackagePrefix.$typeName").kotlin as KClass<out Arguments>
    }
}
