package viaduct.tenant.runtime.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UniqueKeyPartitionTest {
    @Test
    fun `empty input has no groups`() {
        assertEquals(emptyList<List<String>>(), partitionByUniqueKey(emptyList<String>()) { it })
    }

    @Test
    fun `unique input remains in one stable group`() {
        val values = listOf("A1", "B1", "C1")

        val result = partitionByUniqueKey(values) { it.first() }

        assertEquals(listOf(values), result)
    }

    @Test
    fun `interleaved duplicates are assigned by occurrence while preserving order`() {
        val values = listOf("A1", "B1", "A2", "C1", "B2")

        val result = partitionByUniqueKey(values) { it.first() }

        assertEquals(
            listOf(
                listOf("A1", "B1", "C1"),
                listOf("A2", "B2"),
            ),
            result,
        )
    }

    @Test
    fun `group count equals maximum key frequency`() {
        val values = listOf("A1", "B1", "A2", "A3", "B2")

        val result = partitionByUniqueKey(values) { it.first() }

        assertEquals(
            listOf(
                listOf("A1", "B1"),
                listOf("A2", "B2"),
                listOf("A3"),
            ),
            result,
        )
    }
}
