package viaduct.tenant.runtime.internal

import viaduct.api.globalid.GlobalID
import viaduct.api.internal.GlobalIDCodec
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.types.NodeCompositeOutput
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.spi.GlobalIDCodec as ServiceGlobalIDCodec

class InternalContextImpl(
    override val schema: ViaductSchema,
    override val globalIDCodec: ServiceGlobalIDCodec,
    override val reflectionLoader: ReflectionLoader
) : InternalContext {
    // Delegate to internal GlobalIDCodec which contains the typed deserialization logic
    private val typedCodec = GlobalIDCodec(globalIDCodec, reflectionLoader)

    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> = typedCodec.deserialize(serialized)
}
