package viaduct.service.api.spi

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import viaduct.graphql.SourceLocation
import viaduct.service.api.GraphQLError

class ResolverErrorBuilderTest {
    @Test
    fun `test NoOp implementation returns null`() {
        val builder = ResolverErrorBuilder.NOOP
        val result = builder.exceptionToGraphQLError(
            throwable = RuntimeException("test"),
            errorMetadata = ErrorReporter.Metadata()
        )

        assertNull(result)
    }

    @Test
    fun `test functional interface implementation`() {
        val customBuilder = ResolverErrorBuilder { throwable, metadata ->
            listOf(
                GraphQLError(
                    message = "Custom error: ${throwable.message}",
                    path = metadata.executionPath,
                    locations = metadata.sourceLocation?.let { listOf(it) },
                    extensions = mapOf("errorType" to "CUSTOM")
                )
            )
        }

        val metadata = ErrorReporter.Metadata(
            executionPath = listOf("query", "user"),
            sourceLocation = SourceLocation(line = 10, column = 5)
        )

        val result = customBuilder.exceptionToGraphQLError(
            throwable = RuntimeException("Something failed"),
            errorMetadata = metadata
        )

        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals("Custom error: Something failed", result[0].message)
        assertEquals(listOf("query", "user"), result[0].path)
        assertEquals(10, result[0].locations?.first()?.line)
        assertEquals("CUSTOM", result[0].extensions?.get("errorType"))
    }

    @Test
    fun `test builder can return multiple errors`() {
        val multiErrorBuilder = ResolverErrorBuilder { throwable, _ ->
            listOf(
                GraphQLError(message = "Error 1: ${throwable.message}"),
                GraphQLError(message = "Error 2: Related issue"),
                GraphQLError(message = "Error 3: Recommendation")
            )
        }

        val result = multiErrorBuilder.exceptionToGraphQLError(
            throwable = IllegalArgumentException("Invalid input"),
            errorMetadata = ErrorReporter.Metadata()
        )

        assertNotNull(result)
        assertEquals(3, result.size)
        assertEquals("Error 1: Invalid input", result[0].message)
        assertEquals("Error 2: Related issue", result[1].message)
        assertEquals("Error 3: Recommendation", result[2].message)
    }

    @Test
    fun `test builder can selectively handle exceptions`() {
        class CustomException(
            message: String
        ) : RuntimeException(message)

        val selectiveBuilder = ResolverErrorBuilder { throwable, _ ->
            when (throwable) {
                is CustomException -> listOf(
                    GraphQLError(
                        message = "Handled custom exception: ${throwable.message}",
                        extensions = mapOf("errorType" to "CUSTOM_ERROR")
                    )
                )
                is IllegalArgumentException -> listOf(
                    GraphQLError(
                        message = "Invalid argument: ${throwable.message}",
                        extensions = mapOf("errorType" to "INVALID_ARGUMENT")
                    )
                )
                else -> null // Return null for unhandled exceptions
            }
        }

        val customResult = selectiveBuilder.exceptionToGraphQLError(
            throwable = CustomException("Custom failure"),
            errorMetadata = ErrorReporter.Metadata()
        )
        assertNotNull(customResult)
        assertEquals("CUSTOM_ERROR", customResult[0].extensions?.get("errorType"))

        val illegalArgResult = selectiveBuilder.exceptionToGraphQLError(
            throwable = IllegalArgumentException("Bad arg"),
            errorMetadata = ErrorReporter.Metadata()
        )
        assertNotNull(illegalArgResult)
        assertEquals("INVALID_ARGUMENT", illegalArgResult[0].extensions?.get("errorType"))

        val unhandledResult = selectiveBuilder.exceptionToGraphQLError(
            throwable = NullPointerException("null"),
            errorMetadata = ErrorReporter.Metadata()
        )
        assertNull(unhandledResult)
    }

    @Test
    fun `test builder with ErrorBuilder helper`() {
        val builderUsingHelper = ResolverErrorBuilder { throwable, metadata ->
            listOf(
                ErrorBuilder
                    .newError(metadata)
                    .message("Error: ${throwable.message}")
                    .extension("errorType", "INTERNAL_ERROR")
                    .extension("exceptionClass", throwable::class.simpleName)
                    .build()
            )
        }

        val metadata = ErrorReporter.Metadata(
            fieldName = "userName",
            parentType = "User",
            executionPath = listOf("user", "userName"),
            sourceLocation = SourceLocation(line = 5, column = 10)
        )

        val result = builderUsingHelper.exceptionToGraphQLError(
            throwable = RuntimeException("Database connection failed"),
            errorMetadata = metadata
        )

        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals("Error: Database connection failed", result[0].message)
        assertEquals(listOf("user", "userName"), result[0].path)
        assertEquals(5, result[0].locations?.first()?.line)
        assertEquals("INTERNAL_ERROR", result[0].extensions?.get("errorType"))
        assertEquals("RuntimeException", result[0].extensions?.get("exceptionClass"))
    }

    @Test
    fun `test builder returns empty list instead of null`() {
        val emptyListBuilder = ResolverErrorBuilder { _, _ -> emptyList() }

        val result = emptyListBuilder.exceptionToGraphQLError(
            throwable = RuntimeException("error"),
            errorMetadata = ErrorReporter.Metadata()
        )

        assertNotNull(result)
        assertEquals(0, result.size)
    }
}
