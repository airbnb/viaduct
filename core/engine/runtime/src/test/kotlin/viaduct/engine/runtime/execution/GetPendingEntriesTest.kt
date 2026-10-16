package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GetPendingEntriesTest {
    @Test
    fun `no new groups produces no pending entries`() {
        val ctx = mkExecutionParameters()

        GetPendingEntries(ctx, emptyList()).shouldBeEmpty()
    }

    @Test
    fun `entries preserve group order paths and optional labels`() {
        val ctx = mkExecutionParameters()
        val path = ResultPath.rootPath().segment("aliasedItems")
        val groups = listOf(
            DeferDeliveryGroup(path.segment(1), null, null),
            DeferDeliveryGroup(path.segment(0), "", null),
            DeferDeliveryGroup(path.segment(2), "details", null),
        )

        GetPendingEntries(ctx, groups).map { it.toSpecification() }.shouldContainExactly(
            mapOf("id" to "0", "path" to listOf("aliasedItems", 1)),
            mapOf("id" to "1", "path" to listOf("aliasedItems", 0), "label" to ""),
            mapOf("id" to "2", "path" to listOf("aliasedItems", 2), "label" to "details"),
        )
    }

    @Test
    fun `later calls reuse existing ids and allocate new ones`() {
        val ctx = mkExecutionParameters()
        val first = DeferDeliveryGroup(ResultPath.rootPath(), "first", null)
        val second = DeferDeliveryGroup(ResultPath.rootPath(), "second", null)

        assertEquals("0", GetPendingEntries(ctx, listOf(first)).single().id)
        GetPendingEntries(ctx, listOf(second, first)).map { it.id }.shouldContainExactly("1", "0")
    }

    @Test
    fun `separate groups with identical paths and labels have distinct ids`() {
        val ctx = mkExecutionParameters()
        val first = DeferDeliveryGroup(ResultPath.rootPath(), null, null)
        val second = DeferDeliveryGroup(ResultPath.rootPath(), null, null)

        GetPendingEntries(ctx, listOf(first, second)).map { it.id }.shouldContainExactly("0", "1")
    }
}
