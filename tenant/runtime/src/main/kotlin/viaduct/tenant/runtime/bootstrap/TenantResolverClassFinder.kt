package viaduct.tenant.runtime.bootstrap

import kotlin.reflect.KClass
import viaduct.api.internal.ObjectBase
import viaduct.api.types.Arguments

/**
 * Interface for dynamically discovering and loading Viaduct Modern tenant classes at runtime.
 *
 * This finder provides methods to locate various types of tenant-specific components from configured
 * specific package. It supports runtime class discovery for multi-tenant applications where each tenant may have
 * different set of resolver implementations.
 *
 * @see ObjectBase
 * @see Arguments
 */
interface TenantResolverClassFinder {
    /**
     * Discovers all resolver classes within the configured package.
     *
     * @return set of resolver class instances found in the package
     */
    fun resolverClassesInPackage(): Set<Class<*>>

    /**
     * Finds all subtypes of the specified class within the configured package.
     *
     * @param type the base class
     * @return set of classes that extend or implement the specified class
     */
    fun <T : Any?> getSubTypesOf(type: Class<T>): Set<Class<out T>>

    /**
     * Discovers all node resolver classes within the configured package.
     *
     * @return set of node resolver class instances found in the package
     */
    fun nodeResolverForClassesInPackage(): Set<Class<*>>

    /**
     * Loads a generated ObjectBase class by its GraphQL type name.
     *
     * @param typeName the GraphQL type name (e.g., "User", "Product")
     * @return the corresponding generated ObjectBase class
     * @throws ClassNotFoundException if no matching class is found
     */
    fun grtClassForName(typeName: String): KClass<out ObjectBase>

    /**
     * Loads a generated Arguments class by its class name.
     *
     * @param typeName the generated argument class name (e.g., "User_Field_Arguments")
     * @return the corresponding generated Arguments class
     * @throws ClassNotFoundException if no matching class is found
     */
    fun argumentClassForName(typeName: String): KClass<out Arguments>
}
