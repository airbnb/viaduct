package viaduct.graphql.test

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResultJsonAssertionTest {
    @Test
    fun `Map assertJson throws IllegalArgumentException for invalid JSON`() {
        val map = mapOf("key" to "value")

        val exception = assertThrows<IllegalArgumentException> {
            map.assertJson("invalid json {")
        }
        assertEquals("Cannot parse expected JSON", exception.message)
    }

    @Test
    fun `Map assertJson succeeds for matching content`() {
        val map = mapOf("user" to mapOf("name" to "Alice", "age" to 30))

        map.assertJson("""{"user": {"name": "Alice", "age": 30}}""")
    }

    @Test
    fun `Map assertJson throws AssertionError for non-matching content`() {
        val map = mapOf("user" to "Alice")

        assertThrows<AssertionError> {
            map.assertJson("""{"user": "Bob"}""")
        }
    }
}
