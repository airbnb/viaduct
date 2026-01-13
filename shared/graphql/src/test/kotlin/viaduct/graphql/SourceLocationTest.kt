package viaduct.graphql

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class SourceLocationTest {
    @Test
    fun `test create SourceLocation with all fields`() {
        val location = SourceLocation(
            line = 10,
            column = 5,
            sourceName = "query.graphql"
        )

        assertEquals(10, location.line)
        assertEquals(5, location.column)
        assertEquals("query.graphql", location.sourceName)
    }

    @Test
    fun `test create SourceLocation without sourceName`() {
        val location = SourceLocation(
            line = 1,
            column = 1
        )

        assertEquals(1, location.line)
        assertEquals(1, location.column)
        assertNull(location.sourceName)
    }

    @Test
    fun `test SourceLocation data class equality`() {
        val location1 = SourceLocation(line = 5, column = 10, sourceName = "test.graphql")
        val location2 = SourceLocation(line = 5, column = 10, sourceName = "test.graphql")
        val location3 = SourceLocation(line = 5, column = 11, sourceName = "test.graphql")

        assertEquals(location1, location2)
        assert(location1 != location3)
    }

    @Test
    fun `test SourceLocation copy`() {
        val original = SourceLocation(line = 5, column = 10, sourceName = "original.graphql")
        val copied = original.copy(sourceName = "copied.graphql")

        assertEquals(5, copied.line)
        assertEquals(10, copied.column)
        assertEquals("copied.graphql", copied.sourceName)
        assertEquals("original.graphql", original.sourceName)
    }
}
