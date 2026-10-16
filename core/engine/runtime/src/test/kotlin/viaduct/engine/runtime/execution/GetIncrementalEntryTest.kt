package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GetIncrementalEntryTest {
    @Test
    fun `a value at the group path omits subpath and empty errors`() {
        val ctx = mkExecutionParameters()
        val path = ResultPath.rootPath().segment("hero")
        val group = DeferDeliveryGroup(path, "details", null)
        val value = GroupValue(path, mapOf("alias" to null, "friends" to listOf(mapOf("id" to "2"))), emptyList())

        val entry = GetIncrementalEntry(ctx, group, value)

        assertNull(entry.subPath)
        assertEquals(mapOf("id" to "0", "data" to value.data), entry.toSpecification())
    }

    @Test
    fun `empty object data is retained`() {
        val ctx = mkExecutionParameters()
        val group = DeferDeliveryGroup(ResultPath.rootPath(), null, null)
        val value = GroupValue(group.path, emptyMap(), emptyList())

        assertEquals(mapOf("id" to "0", "data" to emptyMap<String, Any?>()), GetIncrementalEntry(ctx, group, value).toSpecification())
    }

    @Test
    fun `subpath removes only the matching prefix`() {
        val ctx = mkExecutionParameters()
        val group = DeferDeliveryGroup(ResultPath.rootPath().segment("node"), null, null)
        val value = GroupValue(group.path.segment("node"), mapOf("id" to 1), emptyList())

        assertEquals(listOf("node"), GetIncrementalEntry(ctx, group, value).subPath?.toList())
    }

    @Test
    fun `errors retain their absolute paths metadata and order`() {
        val ctx = mkExecutionParameters()
        val group = DeferDeliveryGroup(ResultPath.rootPath().segment("items"), null, null)
        val path = group.path.segment(1)
        val errors = listOf(
            GraphQLError.newError().message("denied").path(path.segment("alias")).location(SourceLocation(2, 3)).extensions(mapOf("code" to "DENIED")).build(),
            GraphQLError.newError().message("missing").path(path.segment("other")).build(),
        )
        val value = GroupValue(path, mapOf("alias" to null, "other" to null), errors)

        val entry = GetIncrementalEntry(ctx, group, value)

        entry.errors.shouldContainExactly(errors)
        assertEquals(
            mapOf("id" to "0", "data" to value.data, "subPath" to listOf(1), "errors" to errors.map { it.toSpecification() }),
            entry.toSpecification(),
        )
    }

    @Test
    fun `values outside the group path are rejected`() {
        val ctx = mkExecutionParameters()
        val group = DeferDeliveryGroup(ResultPath.rootPath().segment("items").segment(1), null, null)
        val invalidPaths = listOf(
            ResultPath.rootPath().segment("other").segment(1),
            ResultPath.rootPath().segment("items").segment(0),
            ResultPath.rootPath().segment("items"),
        )

        for (path in invalidPaths) {
            assertThrows<IllegalArgumentException> { GetIncrementalEntry(ctx, group, GroupValue(path, emptyMap(), emptyList())) }
        }
    }
}
