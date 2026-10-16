package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GetCompletedEntryTest {
    @Test
    fun `successful completion contains only an id`() {
        val ctx = mkExecutionParameters()
        val group = DeferDeliveryGroup(ResultPath.rootPath().segment("hero"), "details", null)

        assertEquals(mapOf("id" to "0"), GetCompletedEntry(ctx, group, emptyList()).toSpecification())
    }

    @Test
    fun `completion reuses the id from pending and incremental entries`() {
        val ctx = mkExecutionParameters()
        val first = DeferDeliveryGroup(ResultPath.rootPath(), null, null)
        val second = DeferDeliveryGroup(ResultPath.rootPath(), null, null)
        val pending = GetPendingEntries(ctx, listOf(first, second))
        val incremental = GetIncrementalEntry(ctx, second, GroupValue(second.path, mapOf("value" to 1), emptyList()))

        val completed = GetCompletedEntry(ctx, second, emptyList())

        assertEquals(pending[1].id, completed.id)
        assertEquals(incremental.id, completed.id)
        assertEquals(mapOf("id" to "1"), completed.toSpecification())
    }

    @Test
    fun `completion preserves all errors in order`() {
        val ctx = mkExecutionParameters()
        val group = DeferDeliveryGroup(ResultPath.rootPath().segment("items").segment(1), null, null)
        val errors = listOf(
            GraphQLError.newError().message("denied").path(group.path.segment("alias")).location(SourceLocation(2, 3)).extensions(mapOf("code" to "DENIED")).build(),
            GraphQLError.newError().message("missing").path(group.path.segment("other")).build(),
        )

        val completed = GetCompletedEntry(ctx, group, errors)

        completed.errors.shouldContainExactly(errors)
        assertEquals(mapOf("id" to "0", "errors" to errors.map { it.toSpecification() }), completed.toSpecification())
    }
}
