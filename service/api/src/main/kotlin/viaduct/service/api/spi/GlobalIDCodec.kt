package viaduct.service.api.spi

import viaduct.apiannotations.StableApi

/**
 * GlobalIDCodec provides a way to serialize and deserialize GlobalIDs.
 *
 * This codec is shared across all tenant-API implementations within a Viaduct instance
 * to ensure interoperability between modules from different tenant-APIs. All tenant
 * modules in a single Viaduct instance must use the same codec so that GlobalIDs
 * serialized by one module can be correctly deserialized by another module.
 */
@StableApi
interface GlobalIDCodec {
    /**
     * Serializes a GlobalID into a string representation.
     *
     * @param typeName The GraphQL type name (e.g., "User", "Listing")
     * @param localID The local/internal ID of the node (e.g., "12345")
     * @return The serialized GlobalID string
     */
    fun serialize(
        typeName: String,
        localID: String
    ): String

    /**
     * Deserializes a GlobalID string back into its components.
     *
     * @param globalID The serialized GlobalID string
     * @return A Pair containing the type name (first) and local ID (second)
     * @throws IllegalArgumentException if the globalID string is malformed
     */
    fun deserialize(globalID: String): Pair<String, String>
}
