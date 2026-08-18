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
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.engine.runtime.CheckerDispatcher
import viaduct.engine.runtime.EngineObjectDataFactory
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeViaductModernGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

/**
 * Behavioral coverage for the `@bypassPolicyCheck`-driven access-check bypass during completion.
 *
 * The bypass decision is made by [FieldCompleter.shouldBypassChecker]. The Airbnb-specific
 * directive is retained by the full internal schema and is used by internal execution documents,
 * including Required Selection Sets. Public Base and scoped schemas remove the directive
 * definition before client validation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FieldCompleterBypassPolicyCheckTest {
    private val sdl = """
        directive @bypassPolicyCheck on FIELD
        type Query {
            field: String
            helper: String
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
        requiredSelectionSetRegistry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty,
    ) = executeViaductModernGraphQL(
        sdl = sdl,
        resolvers = resolvers,
        query = query,
        fieldCheckerDispatchers = mapOf(coordinate to failingChecker()),
        airbnbBypassPolicyCheckDuringCompletion = airbnbBypassPolicyCheckDuringCompletion,
        requiredSelectionSetRegistry = requiredSelectionSetRegistry,
    )

    @Test
    fun `flag on and internal RSS directive bypasses the checker`() =
        runExecutionTest {
            val result = execute(
                query = "{ field }",
                airbnbBypassPolicyCheckDuringCompletion = true,
                coordinate = "Query" to "helper",
                resolvers = mapOf(
                    "Query" to mapOf(
                        "field" to DataFetcher { "hello" },
                        "helper" to DataFetcher { "internal helper value" },
                    )
                ),
                requiredSelectionSetRegistry = MockRequiredSelectionSetRegistry.builder()
                    .fieldResolverEntry("Query" to "field", "helper @bypassPolicyCheck")
                    .build(),
            )

            assertTrue(result.errors.isEmpty(), "expected no errors when the internal RSS checker is bypassed")
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
}
