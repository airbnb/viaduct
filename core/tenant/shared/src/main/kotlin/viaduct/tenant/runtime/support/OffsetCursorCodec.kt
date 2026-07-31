package viaduct.tenant.runtime.support

import java.util.Base64
import viaduct.apiannotations.InternalApi

/**
 * The encode/decode logic for offset-based pagination cursors, shared by the Kotlin and Java Tenant
 * APIs so both produce byte-identical cursor strings and are tested once.
 *
 * A cursor is `Base64URL-no-padding("__viaduct:idx:<offset>")`. The public `OffsetCursor` types in
 * each language (`viaduct.api.types.OffsetCursor`, `viaduct.java.api.types.OffsetCursor`) are thin
 * wrappers that delegate here.
 */
@InternalApi
object OffsetCursorCodec {
    private const val CURSOR_PREFIX = "__viaduct"
    private const val OFFSET_MARKER = "idx"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** Encodes a non-negative [offset] into its cursor string. */
    fun encode(offset: Int): String {
        require(offset >= 0) { "Offset must be non-negative: $offset" }
        val payload = "$CURSOR_PREFIX:$OFFSET_MARKER:$offset"
        return encoder.encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    /**
     * Decodes a cursor string to its offset.
     *
     * @throws IllegalArgumentException if the cursor is malformed, uses an unknown format, or
     *     encodes a negative offset
     */
    fun decode(cursor: String): Int {
        val decoded = try {
            String(decoder.decode(cursor), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid cursor (bad Base64): $cursor", e)
        }

        val parts = decoded.split(":")
        require(parts.size == 3 && parts[0] == CURSOR_PREFIX && parts[1] == OFFSET_MARKER) {
            "Invalid cursor format: $cursor"
        }

        val offset = parts[2].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid cursor (non-numeric offset): $cursor")

        require(offset >= 0) { "Invalid cursor (negative offset): $cursor" }

        return offset
    }

    /** Returns true if [cursor] is a well-formed, decodable cursor string. */
    fun isValid(cursor: String): Boolean =
        try {
            decode(cursor)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
}
