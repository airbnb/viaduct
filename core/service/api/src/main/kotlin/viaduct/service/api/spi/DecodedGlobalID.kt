package viaduct.service.api.spi

import viaduct.apiannotations.ExcludeFromJacocoGeneratedReport
import viaduct.apiannotations.StableApi

/**
 * The decoded components of a serialized GlobalID, as produced by [GlobalIDCodec.deserialize].
 *
 * @property typeName The GraphQL type name (e.g., "User", "Listing").
 * @property localID The local/internal ID of the node (e.g., "12345").
 */
@StableApi
@ExcludeFromJacocoGeneratedReport
data class DecodedGlobalID(
    val typeName: String,
    val localID: String,
)
