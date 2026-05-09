package viaduct.tenant.runtime.internal

import kotlin.reflect.full.isSubclassOf
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.GRTConvFactory
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.engine.api.ViaductSchema
import viaduct.errors.TenantUsageException
import viaduct.service.api.spi.GlobalIDCodec as ServiceGlobalIDCodec

class InternalContextImpl(
    override val schema: ViaductSchema,
    override val globalIDCodec: ServiceGlobalIDCodec,
    override val reflectionLoader: ReflectionLoader,
    override val grtConvFactory: GRTConvFactory,
) : InternalContext {
    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> {
        val (typeName, localID) = try {
            globalIDCodec.deserialize(serialized)
        } catch (e: IllegalArgumentException) {
            throw TenantUsageException("Invalid GlobalID: \"$serialized\"", e)
        }

        val type = reflectionLoader.reflectionFor(typeName).let {
            require(it.kcls.isSubclassOf(NodeObject::class)) {
                "type `$typeName` from GlobalID '$serialized' is not a NodeObject"
            }
            @Suppress("UNCHECKED_CAST")
            it as Type<T>
        }

        return GlobalID(type, localID)
    }
}
