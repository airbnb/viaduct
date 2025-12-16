package viaduct.api.globalid

import viaduct.api.types.NodeCompositeOutput
import viaduct.apiannotations.StableApi

/**
 * GlobalIDCodec provides a way to serialize and deserialize GlobalIDs.
 */
@StableApi
interface GlobalIDCodec {
    /**
     * Serialize a GlobalID to a string.
     * @param id The GlobalID to serialize.
     * @return The serialized GlobalID.
     */
    fun <T : NodeCompositeOutput> serialize(id: GlobalID<T>): String

    /**
     * Deserialize a GlobalID from a string.
     * @param str The string to deserialize.
     * @return The deserialized GlobalID.
     */
    fun <T : NodeCompositeOutput> deserialize(str: String): GlobalID<T>
}
