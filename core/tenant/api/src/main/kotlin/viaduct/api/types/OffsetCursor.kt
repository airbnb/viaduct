package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi
import viaduct.tenant.runtime.support.OffsetCursorCodec

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
 * The encode/decode logic lives in the language-neutral [OffsetCursorCodec] so the Kotlin and Java
 * Tenant APIs produce byte-identical cursors; this value class is a thin typed wrapper over it.
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
    fun toOffset(): Int = OffsetCursorCodec.decode(value)

    companion object {
        /**
         * Create an OffsetCursor from an offset value.
         * Encodes the offset into the cursor string format.
         *
         * @param offset The zero-based offset position
         * @return An OffsetCursor containing the encoded cursor string
         * @throws IllegalArgumentException if offset is negative
         */
        @ExperimentalApi
        fun fromOffset(offset: Int): OffsetCursor = OffsetCursor(OffsetCursorCodec.encode(offset))

        /**
         * Check if a cursor string is valid without throwing.
         *
         * @param cursor The cursor string to validate
         * @return true if cursor is valid and decodable
         */
        @ExperimentalApi
        fun isValid(cursor: String): Boolean = OffsetCursorCodec.isValid(cursor)
    }
}
