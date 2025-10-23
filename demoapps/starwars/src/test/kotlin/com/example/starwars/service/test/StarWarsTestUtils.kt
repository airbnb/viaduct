package com.example.starwars.service.test

import viaduct.api.internal.ReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput
import viaduct.tenant.runtime.globalid.GlobalIDCodecImpl
import viaduct.tenant.runtime.globalid.GlobalIDImpl

/**
 * Generate a Global ID string for a given type and internal ID.
 */
fun Type<NodeCompositeOutput>.globalId(internalId: String): String {
    // Simple stub mirror for GlobalIDCodec (only used for serialization in tests)
    val globalIDCodec = GlobalIDCodecImpl(object : ReflectionLoader {
        override fun reflectionFor(name: String) = throw UnsupportedOperationException("Deserialization not needed in tests")

        override fun getGRTKClassFor(name: String) = throw UnsupportedOperationException("Deserialization not needed in tests")
    })

    val globalId = GlobalIDImpl(this, internalId)
    return globalIDCodec.serialize(globalId)
}
