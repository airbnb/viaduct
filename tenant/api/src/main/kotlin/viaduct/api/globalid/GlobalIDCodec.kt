package viaduct.api.globalid

import viaduct.api.types.NodeCompositeOutput
import viaduct.apiannotations.StableApi

/**
 * GlobalIDCodec provides a way to serialize and deserialize GlobalIDs.
 *
 * This interface provides typed GlobalID operations for use within the Viaduct tenant API.
 * The implementation wraps the service-level [viaduct.service.api.spi.GlobalIDCodec] and
 * adds type information via reflection.
 *
 * Note: Tenant code should prefer using the methods on ExecutionContext
 * (e.g., globalIDStringFor, deserializeGlobalID) rather than using this interface directly.
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
