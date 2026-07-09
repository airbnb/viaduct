@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.schema.DataFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.EngineObjectDataFactory
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeViaductModernGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

/**
 * Behavioral coverage for the `@bypassPolicyCheck`-driven access-check bypass during completion.
 *
 * The bypass decision is made by [FieldCompleter.shouldBypassChecker]:
 * ```
 * parameters.bypassChecksDuringCompletion ||
 *     (airbnbBypassPolicyCheckDuringCompletion && field.mergedField.singleField.hasDirective("bypassPolicyCheck"))
 * ```
 *
 * These tests exercise it end-to-end through [executeViaductModernGraphQL] with a failing field
 * checker: when the checker is bypassed the field resolves to its value with no errors; otherwise
 * the checker error surfaces and the (nullable) field is null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FieldCompleterBypassPolicyCheckTest {
    private val sdl = """
        directive @bypassPolicyCheck on FIELD
        type Query {
            field: String
            items: [String]
        }
    """.trimIndent()

    /** A field checker that always denies access. */
    private fun failingChecker(): CheckerDispatcher {
        val checkError = IllegalAccessException("permission denied")
        return object : CheckerDispatcher {
            override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap()
            override lateinit var executor: CheckerExecutor

            override suspend fun execute(
                arguments: Map<String, Any?>,
                objectDataFactories: Map<String, EngineObjectDataFactory>,
                context: EngineExecutionContext,
                checkerType: CheckerExecutor.CheckerType,
            ): CheckerResult =
                object : CheckerResult.Error {
                    override val error = checkError

                    override fun isErrorForResolver(ctx: CheckerResultContext) = true

                    override fun combine(fieldResult: CheckerResult.Error) = this
                }
        }.also { dispatcher ->
            dispatcher.executor = object : CheckerExecutor {
                override suspend fun execute(
                    arguments: Map<String, Any?>,
                    objectDataMap: Map<String, EngineObjectData.Sync>,
                    context: EngineExecutionContext,
                    checkerType: CheckerExecutor.CheckerType,
                ) = dispatcher.execute(arguments, emptyMap(), context, checkerType)

                override val checkerMetadata = null
                override val requiredSelectionSets = emptyMap<String, RequiredSelectionSet?>()
            }
        }
    }

    private suspend fun execute(
        query: String,
        airbnbBypassPolicyCheckDuringCompletion: Boolean,
        coordinate: Coordinate,
        resolvers: Map<String, Map<String, DataFetcher<*>>>,
    ) = executeViaductModernGraphQL(
        sdl = sdl,
        resolvers = resolvers,
        query = query,
        fieldCheckerDispatchers = mapOf(coordinate to failingChecker()),
        airbnbBypassPolicyCheckDuringCompletion = airbnbBypassPolicyCheckDuringCompletion,
    )

    @Test
    fun `flag on and directive present bypasses the checker`() =
        runExecutionTest {
            val result = execute(
                query = "{ field @bypassPolicyCheck }",
                airbnbBypassPolicyCheckDuringCompletion = true,
                coordinate = "Query" to "field",
                resolvers = mapOf("Query" to mapOf("field" to DataFetcher { "hello" })),
            )

            assertTrue(result.errors.isEmpty(), "expected no errors when the checker is bypassed")
            assertEquals("hello", (result.getData<Map<String, Any?>>())["field"])
        }

    @Test
    fun `flag on but no directive enforces the checker`() =
        runExecutionTest {
            val result = execute(
                query = "{ field }",
                airbnbBypassPolicyCheckDuringCompletion = true,
                coordinate = "Query" to "field",
                resolvers = mapOf("Query" to mapOf("field" to DataFetcher { "hello" })),
            )

            assertTrue(result.errors.isNotEmpty(), "expected the checker error to surface")
            assertNull((result.getData<Map<String, Any?>>())["field"])
        }

    @Test
    fun `flag off ignores the directive and enforces the checker`() =
        runExecutionTest {
            val result = execute(
                query = "{ field @bypassPolicyCheck }",
                airbnbBypassPolicyCheckDuringCompletion = false,
                coordinate = "Query" to "field",
                resolvers = mapOf("Query" to mapOf("field" to DataFetcher { "hello" })),
            )

            assertTrue(result.errors.isNotEmpty(), "expected the checker error to surface")
            assertNull((result.getData<Map<String, Any?>>())["field"])
        }

    @Test
    fun `list completion bypasses the checker the same way as object field completion`() =
        runExecutionTest {
            val result = execute(
                query = "{ items @bypassPolicyCheck }",
                airbnbBypassPolicyCheckDuringCompletion = true,
                coordinate = "Query" to "items",
                resolvers = mapOf("Query" to mapOf("items" to DataFetcher { listOf("a", "b") })),
            )

            assertTrue(result.errors.isEmpty(), "expected no errors when the checker is bypassed")
            assertEquals(listOf("a", "b"), (result.getData<Map<String, Any?>>())["items"])
        }
}
