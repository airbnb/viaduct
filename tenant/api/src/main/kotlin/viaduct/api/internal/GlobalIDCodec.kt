package viaduct.api.internal

import kotlin.reflect.full.isSubclassOf
import viaduct.api.globalid.GlobalID
import viaduct.api.globalid.GlobalIDCodec as TenantGlobalIDCodec
import viaduct.api.globalid.GlobalIDImpl
import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.service.api.spi.GlobalIDCodec as ServiceGlobalIDCodec

/**
 * Internal implementation of [TenantGlobalIDCodec] that wraps the service-level codec.
 *
 * This class provides the typed GlobalID API used internally by the tenant API.
 * It delegates serialization/deserialization to the service-level codec and uses
 * [ReflectionLoader] to reconstruct type information when deserializing.
 *
 * This is an internal implementation class - tenants should use the methods on
 * ExecutionContext (e.g., globalIDStringFor, deserializeGlobalID) instead.
 *
 * @param serviceGlobalIDCodec The service-level GlobalIDCodec for string-based operations
 * @param reflectionLoader The reflection loader for type reconstruction
 */
class GlobalIDCodec(
    private val serviceGlobalIDCodec: ServiceGlobalIDCodec,
    private val reflectionLoader: ReflectionLoader
) : TenantGlobalIDCodec {
    /**
     * Serializes a GlobalID to a string by delegating to the service-level codec.
     */
    override fun <T : NodeCompositeOutput> serialize(id: GlobalID<T>): String = serviceGlobalIDCodec.serialize(id.type.name, id.internalID)

    /**
     * Deserializes a GlobalID string back into a typed GlobalID object.
     *
     * Uses the service-level codec to decode the string, then reconstructs
     * the type information using the reflection loader.
     *
     * @throws IllegalArgumentException if the type is not a NodeObject
     */
    override fun <T : NodeCompositeOutput> deserialize(str: String): GlobalID<T> {
        val (typeName, localID) = serviceGlobalIDCodec.deserialize(str)

        val type = reflectionLoader.reflectionFor(typeName).let {
            require(it.kcls.isSubclassOf(NodeObject::class)) {
                "type `$typeName` from GlobalID '$str' is not a NodeObject"
            }
            @Suppress("UNCHECKED_CAST")
            it as Type<T>
        }

        return GlobalIDImpl(type, localID)
    }
}
