package viaduct.service.api.spi.globalid

import com.google.common.escape.Escaper
import com.google.common.net.UrlEscapers
import java.net.URLDecoder
import java.util.Base64
import viaduct.apiannotations.StableApi
import viaduct.service.api.spi.GlobalIDCodec

/**
 * This object provides the canonical Base64-encoded format used by Viaduct for GlobalIDs.
 */
@StableApi
object GlobalIDCodecDefault : GlobalIDCodec {
    private val enc = Base64.getEncoder()
    private val dec = Base64.getDecoder()
    private val escaper: Escaper by lazy { UrlEscapers.urlFormParameterEscaper() }
    private const val DELIM = ":"

    /**
     * Serializes a GlobalID into Base64-encoded string format.
     *
     * @param typeName The GraphQL type name (e.g., "User")
     * @param localID The local/internal ID, which may contain special characters
     * @return Base64-encoded string in format: Base64("typeName:urlEncodedLocalID")
     */
    override fun serialize(
        typeName: String,
        localID: String
    ): String =
        enc.encodeToString(
            "$typeName$DELIM${escaper.escape(localID)}".toByteArray()
        )

    /**
     * Deserializes a Base64-encoded GlobalID string back into its components.
     *
     * @param globalID The Base64-encoded GlobalID string
     * @return Pair of (typeName, localID) with URL-decoding applied to localID
     * @throws IllegalArgumentException if the globalID is malformed or not valid Base64
     */
    override fun deserialize(globalID: String): Pair<String, String> =
        try {
            val decoded = dec.decode(globalID).decodeToString()
            val parts = decoded.split(DELIM, limit = 2)
            require(parts.size == 2) {
                "Expected GlobalID to have format '<typeName>$DELIM<localID>' after Base64 decoding, " +
                    "but got: \"$decoded\" (from input: \"$globalID\")"
            }

            val (typeName, encodedLocalID) = parts
            val localID = URLDecoder.decode(encodedLocalID, Charsets.UTF_8.name())

            Pair(typeName, localID)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Failed to deserialize GlobalID: \"$globalID\". ${e.message}",
                e
            )
        }
}
