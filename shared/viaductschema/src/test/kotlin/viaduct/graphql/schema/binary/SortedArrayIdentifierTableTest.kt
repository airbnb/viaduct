package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SortedArrayIdentifierTableTest {
    @Test
    fun `get returns index for existing key`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("apple", "banana", "cherry"))
        assertEquals(0, table.get("apple"))
        assertEquals(1, table.get("banana"))
        assertEquals(2, table.get("cherry"))
    }

    @Test
    fun `get throws NoSuchElementException for missing key`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("apple", "banana"))
        assertThrows(NoSuchElementException::class.java) {
            table.get("orange")
        }
    }

    @Test
    fun `find returns index for existing key`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("apple", "banana", "cherry"))
        assertEquals(0, table.find("apple"))
        assertEquals(1, table.find("banana"))
        assertEquals(2, table.find("cherry"))
    }

    @Test
    fun `find returns null for missing key`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("apple", "banana"))
        assertNull(table.find("orange"))
    }

    @Test
    fun `keyAt returns string at index`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("cherry", "apple", "banana"))
        // Strings are sorted
        assertEquals("apple", table.keyAt(0))
        assertEquals("banana", table.keyAt(1))
        assertEquals("cherry", table.keyAt(2))
    }

    @Test
    fun `keyAt throws IndexOutOfBoundsException for invalid index`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("apple", "banana"))
        assertThrows(IndexOutOfBoundsException::class.java) {
            table.keyAt(5)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            table.keyAt(-1)
        }
    }

    @Test
    fun `size returns correct count`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("apple", "banana", "cherry"))
        assertEquals(3, table.size)
    }

    @Test
    fun `empty table has size zero`() {
        val table = SortedArrayIdentifierTable.fromStrings(emptyList())
        assertEquals(0, table.size)
    }

    @Test
    fun `single element table works correctly`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("only"))
        assertEquals(1, table.size)
        assertEquals(0, table.get("only"))
        assertEquals("only", table.keyAt(0))
    }

    @Test
    fun `duplicates are deduplicated`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("apple", "banana", "apple", "banana", "apple"))
        assertEquals(2, table.size)
        assertEquals("apple", table.keyAt(0))
        assertEquals("banana", table.keyAt(1))
    }

    // Lookup tests

    @Test
    fun `lookup returns null for no matches`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("apple", "banana", "cherry"))
        assertNull(table.lookup("xyz"))
    }

    @Test
    fun `lookup with exact match`() {
        // "app" starts with "app", "apple" starts with "app", "application" starts with "app"
        val table = SortedArrayIdentifierTable.fromStrings(listOf("app", "apple", "application", "banana"))
        val result = table.lookup("app")
        assertNotNull(result)
        assertEquals(0, result!!.start)
        assertEquals(3, result.endExclusive)
        assertTrue(result.exactMatch) // "app" is in the table
    }

    @Test
    fun `lookup with exact match single result`() {
        // Only "apple" starts with "apple" (not "application" since 'e' != 'i' at position 4)
        val table = SortedArrayIdentifierTable.fromStrings(listOf("app", "apple", "application", "banana"))
        val result = table.lookup("apple")
        assertNotNull(result)
        assertEquals(1, result!!.start)
        assertEquals(2, result.endExclusive)
        assertTrue(result.exactMatch)
    }

    @Test
    fun `lookup with prefix match but no exact match`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("application", "applied", "apply", "banana"))
        val result = table.lookup("appl")
        assertNotNull(result)
        assertEquals(0, result!!.start)
        assertEquals(3, result.endExclusive)
        assertFalse(result.exactMatch)
    }

    @Test
    fun `lookup with empty prefix matches all`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("apple", "banana", "cherry"))
        val result = table.lookup("")
        assertNotNull(result)
        assertEquals(0, result!!.start)
        assertEquals(3, result.endExclusive)
        assertFalse(result.exactMatch) // Empty string is not in table
    }

    @Test
    fun `lookup result is iterable`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("app", "apple", "application", "banana"))
        val result = table.lookup("app")
        assertNotNull(result)
        val indices = result!!.toList()
        assertEquals(listOf(0, 1, 2), indices)
    }

    @Test
    fun `lookup result contains works correctly`() {
        val table = SortedArrayIdentifierTable.fromStrings(listOf("app", "apple", "application", "banana"))
        val result = table.lookup("app")!!
        assertTrue(result.contains(0))
        assertTrue(result.contains(1))
        assertTrue(result.contains(2))
        assertFalse(result.contains(3))
        assertFalse(result.contains(-1))
    }

    @Test
    fun `many strings preserves order and lookup`() {
        val original = (1..100).map { "identifier_$it" }
        val table = SortedArrayIdentifierTable.fromStrings(original)

        assertEquals(100, table.size)

        // Verify lookup still works
        val result = table.lookup("identifier_1")
        assertNotNull(result)
        assertTrue(result!!.exactMatch)

        // Verify all strings are findable
        for (s in original) {
            assertNotNull(table.find(s), "Should find $s")
        }
    }
}
