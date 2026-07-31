package viaduct.tenant.runtime.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Single source of truth for offset-cursor encode/decode behavior. The Kotlin and Java Tenant API
 * `OffsetCursor` types are thin wrappers over [OffsetCursorCodec], so this is where the logic — and
 * the cross-language byte-compatibility contract — is tested.
 */
class OffsetCursorCodecTest {
    @Test
    fun `encode then decode round-trips for zero, small, and large values`() {
        for (offset in listOf(0, 1, 42, 1_000_000, Int.MAX_VALUE)) {
            assertEquals(offset, OffsetCursorCodec.decode(OffsetCursorCodec.encode(offset)))
        }
    }

    // The encoded strings below are the byte-for-byte contract: Base64-URL, no padding, UTF-8
    // payload "__viaduct:idx:<offset>". Cursors are exchanged between the Kotlin and Java Tenant
    // APIs (and returned to clients), so these literals must not change without a coordinated
    // migration.
    @Test
    fun `encode produces the stable cursor string for known offsets`() {
        assertEquals("X192aWFkdWN0OmlkeDow", OffsetCursorCodec.encode(0))
        assertEquals("X192aWFkdWN0OmlkeDox", OffsetCursorCodec.encode(1))
        assertEquals("X192aWFkdWN0OmlkeDoy", OffsetCursorCodec.encode(2))
        assertEquals("X192aWFkdWN0OmlkeDo3", OffsetCursorCodec.encode(7))
        assertEquals("X192aWFkdWN0OmlkeDo5", OffsetCursorCodec.encode(9))
        assertEquals("X192aWFkdWN0OmlkeDo0Mg", OffsetCursorCodec.encode(42))
    }

    @Test
    fun `encode throws for a negative offset`() {
        assertThrows<IllegalArgumentException> { OffsetCursorCodec.encode(-1) }
    }

    @Test
    fun `decode throws when the value is not valid Base64`() {
        assertThrows<IllegalArgumentException> { OffsetCursorCodec.decode("not valid base64!!") }
    }

    @Test
    fun `decode throws when the decoded payload has the wrong format`() {
        // Base64-URL-no-pad of "garbage" — decodes cleanly but is not "__viaduct:idx:<n>".
        assertThrows<IllegalArgumentException> { OffsetCursorCodec.decode("Z2FyYmFnZQ") }
    }

    @Test
    fun `decode throws when the decoded offset is negative`() {
        // Base64-URL-no-pad of "__viaduct:idx:-5".
        assertThrows<IllegalArgumentException> { OffsetCursorCodec.decode("X192aWFkdWN0OmlkeDotNQ") }
    }

    @Test
    fun `isValid is true for a well-formed cursor and false (no throw) otherwise`() {
        assertTrue(OffsetCursorCodec.isValid("X192aWFkdWN0OmlkeDow"))
        assertFalse(OffsetCursorCodec.isValid("not valid base64!!"))
        assertFalse(OffsetCursorCodec.isValid("Z2FyYmFnZQ"))
        assertFalse(OffsetCursorCodec.isValid("X192aWFkdWN0OmlkeDotNQ"))
    }
}
