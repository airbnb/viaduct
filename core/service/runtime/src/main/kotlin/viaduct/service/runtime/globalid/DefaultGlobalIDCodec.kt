package viaduct.service.runtime.globalid

import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Default implementation of GlobalIDCodec used when no custom codec is configured.
 */
class DefaultGlobalIDCodec : GlobalIDCodec {
    override fun serialize(
        typeName: String,
        localID: String
    ): String = GlobalIDCodecDefault.serialize(typeName, localID)

    override fun deserialize(globalID: String): Pair<String, String> = GlobalIDCodecDefault.deserialize(globalID)
}
