package viaduct.engine.api

import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.language.SourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IncrementalExecutionResultTest {
    @Test
    fun `pending entries omit absent labels and preserve the root path`() {
        val entry = PendingEntry("pending-id", ResultPath.rootPath(), null)

        assertEquals(mapOf("id" to "pending-id", "path" to emptyList<Any>()), entry.toSpecification())
    }

    @Test
    fun `pending entries preserve labels and mixed field and list paths`() {
        val path = ResultPath.rootPath().segment("aliasedItems").segment(2).segment("details")

        for (label in listOf("details", "")) {
            val entry = PendingEntry("pending-id", path, label)

            assertEquals(
                mapOf("id" to "pending-id", "path" to listOf("aliasedItems", 2, "details"), "label" to label),
                entry.toSpecification(),
            )
        }
    }

    @Test
    fun `object entries preserve data and omit absent errors and subpaths`() {
        val data = mapOf("name" to "Ada", "details" to mapOf("value" to null))
        val entry = IncrementalEntry.Object("object-id", data, emptyList(), null)

        assertEquals(mapOf("id" to "object-id", "data" to data), entry.toSpecification())
    }

    @Test
    fun `object entries serialize errors and subpaths`() {
        val subPath = ResultPath.rootPath().segment("items").segment(1)
        val errors = listOf(
            GraphQLError.newError()
                .message("denied")
                .path(subPath.segment("name"))
                .location(SourceLocation(2, 3))
                .extensions(mapOf("code" to "DENIED"))
                .build(),
            GraphQLError.newError().message("missing").path(subPath.segment("other")).build(),
        )
        val entry = IncrementalEntry.Object("object-id", emptyMap(), errors, subPath)

        assertEquals(
            mapOf(
                "id" to "object-id",
                "data" to emptyMap<String, Any?>(),
                "errors" to errors.map { it.toSpecification() },
                "subPath" to listOf("items", 1),
            ),
            entry.toSpecification(),
        )
    }

    @Test
    fun `successful completions contain only an id`() {
        val entry = CompletedEntry("completed-id", emptyList())

        assertEquals(mapOf("id" to "completed-id"), entry.toSpecification())
    }

    @Test
    fun `failed completions serialize all errors in order`() {
        val errors = listOf(
            GraphQLError.newError().message("first failure").build(),
            GraphQLError.newError().message("second failure").build(),
        )
        val entry = CompletedEntry("completed-id", errors)

        assertEquals(
            mapOf("id" to "completed-id", "errors" to errors.map { it.toSpecification() }),
            entry.toSpecification(),
        )
    }
}
