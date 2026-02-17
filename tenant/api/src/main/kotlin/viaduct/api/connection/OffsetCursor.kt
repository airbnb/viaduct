package viaduct.api.connection

import java.util.Base64
import viaduct.apiannotations.ExperimentalApi

/**
 * A cursor for offset-based pagination.
 *
 * OffsetCursor wraps an encoded cursor string that represents a position
 * in a paginated list. The cursor format is: Base64("__viaduct:idx:<offset>")
 *
 * Key Design:
 * - `value: String` holds the **encoded cursor string** (e.g., "X192aWFkdWN0OmlkeDow")
 * - `toOffset()` **decodes** the cursor string to get the offset Int
 * - `fromOffset(offset)` **creates** an OffsetCursor by encoding the offset
 *
 * Usage:
 * ```kotlin
 * // Create cursor from offset
 * val cursor = OffsetCursor.fromOffset(42)
 *
 * // Get the encoded string value for GraphQL response
 * val cursorString: String = cursor.value  // "X192aWFkdWN0OmlkeDo0Mg"
 *
 * // Decode cursor back to offset
 * val offset: Int = cursor.toOffset()  // 42
 *
 * // From incoming cursor string
 * val incomingCursor = OffsetCursor(cursorString)
 * val decodedOffset = incomingCursor.toOffset()
 * ```
 *
 * @property value The encoded cursor string (Base64 encoded)
 */
@ExperimentalApi
@JvmInline
value class OffsetCursor(val value: String) {
    /**
     * Decode this cursor to get the offset value.
     *
     * @return The decoded offset
     * @throws IllegalArgumentException if cursor is invalid, malformed, or uses unknown format
     */
    fun toOffset(): Int {
        val decoded = try {
            String(decoder.decode(value), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid cursor (bad Base64): $value", e)
        }

        val parts = decoded.split(":")
        require(parts.size == 3 && parts[0] == CURSOR_PREFIX && parts[1] == OFFSET_MARKER) {
            "Invalid cursor format: $value"
        }

        val offset = parts[2].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid cursor (non-numeric offset): $value")

        require(offset >= 0) { "Invalid cursor (negative offset): $value" }

        return offset
    }

    companion object {
        private const val CURSOR_PREFIX = "__viaduct"
        private const val OFFSET_MARKER = "idx"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        /**
         * Create an OffsetCursor from an offset value.
         * Encodes the offset into the cursor string format.
         *
         * @param offset The zero-based offset position
         * @return An OffsetCursor containing the encoded cursor string
         * @throws IllegalArgumentException if offset is negative
         */
        @ExperimentalApi
        fun fromOffset(offset: Int): OffsetCursor {
            require(offset >= 0) { "Offset must be non-negative: $offset" }
            val payload = "$CURSOR_PREFIX:$OFFSET_MARKER:$offset"
            return OffsetCursor(encoder.encodeToString(payload.toByteArray(Charsets.UTF_8)))
        }

        /**
         * Check if a cursor string is valid without throwing.
         *
         * @param cursor The cursor string to validate
         * @return true if cursor is valid and decodable
         */
        @ExperimentalApi
        fun isValid(cursor: String): Boolean {
            return try {
                OffsetCursor(cursor).toOffset()
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }
}
