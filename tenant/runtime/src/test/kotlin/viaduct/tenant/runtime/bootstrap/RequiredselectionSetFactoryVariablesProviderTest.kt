@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.bootstrap

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.mocks.mockReflectionLoader
import viaduct.api.types.Arguments
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.resolve
import viaduct.engine.api.select.SelectionsParser
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.context.VariablesProviderContextImpl
import viaduct.tenant.runtime.context.factory.VariablesProviderContextFactory
import viaduct.tenant.runtime.internal.InternalContextImpl
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/**
 * Tests for [RequiredSelectionSetFactory] constructs [RequiredSelectionSet]s
 * whose [RequiredSelectionSet.variablesResolvers] fields behave as expected.
 *
 * These tests create RequiredSelectionSets then invoke their VariablesResolvers
 * to validate runtime behavior:
 * - VariablesProvider returning declared variables
 * - VariablesProvider not returning declared variables (error)
 * - VariablesProvider returning undeclared variables (error)
 * - VariablesProvider throwing exceptions
 * - Values passed through without type validation
 *
 * These tests use a simple Factory.const(MockArguments()) for the argumentsFactory since they
 * focus on VariablesProvider execution behavior, not argument construction.
 */
class RequiredselectionSetFactoryVariablesProviderTest {
    private val defaultSchema = MockSchema.mk(
        """
        extend type Query {
            foo(x:Int!):Int!,
            testField(
                nonNullableInt: Int!,
                intList: [Int!]!,
                stringList: [String]
            ): String
        }
        """.trimIndent()
    )

    private val objectData = mkEngineObjectData(defaultSchema.schema.queryType, emptyMap())
    private val vresolveCtx = VariablesResolver.ResolveCtx(
        objectData,
        emptyMap(),
        mockk {
            every { fullSchema } returns defaultSchema
            every { requestContext } returns null
        }
    )

    private fun mkFactory(): RequiredSelectionSetFactory =
        RequiredSelectionSetFactory(
            GlobalIDCodecDefault,
            mockReflectionLoader("viaduct.api.bootstrap.test.grts"),
        )

    class MockArguments : Arguments

    private class MockVariablesProvider(val vars: Map<String, Any?> = emptyMap()) : VariablesProvider<MockArguments> {
        override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> = vars
    }

    private class ThrowingVariablesProvider(private val exception: Exception) : VariablesProvider<MockArguments> {
        override suspend fun provide(context: VariablesProviderContext<MockArguments>): Map<String, Any?> {
            throw exception
        }
    }

    /**
     * Simple VariablesProviderContextFactory that returns empty MockArguments for all behavioral tests.
     * These tests focus on VariablesProvider execution, not argument construction.
     */
    private val variablesProviderContextFactory = object : VariablesProviderContextFactory {
        override fun createVariablesProviderContext(
            engineExecutionContext: viaduct.engine.api.EngineExecutionContext,
            requestContext: Any?,
            rawArguments: Map<String, Any?>
        ): VariablesProviderContext<Arguments> {
            val ic = InternalContextImpl(
                engineExecutionContext.fullSchema,
                GlobalIDCodecDefault,
                mockReflectionLoader("viaduct.api.bootstrap.test.grts")
            )
            return VariablesProviderContextImpl(ic, requestContext, MockArguments())
        }
    }

    @Test
    fun `mkRequiredSelectionSets -- VariablesProvider that does not return declared variable should throw at request time`(): Unit =
        runBlocking {
            val objectSelections = SelectionsParser.parse("Query", "foo(x: \$requiredVar)")
            val rss = mkFactory().mkRequiredSelectionSets(
                variablesProvider = VariablesProviderInfo(setOf("requiredVar")) { MockVariablesProvider(emptyMap()) }, // Declares but doesn't provide
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = emptyList(),
            ).objectSelections

            assertThrows<IllegalStateException> {
                rss!!.variablesResolvers.resolve(vresolveCtx)
            }
        }

    @Test
    fun `mkRequiredSelectionSets -- VariablesProvider values are passed through without type validation`(): Unit =
        runBlocking {
            // Test that all VariablesProvider values are passed through as-is without GraphQL type validation
            val objectSelections = SelectionsParser.parse("Query", "testField(nonNullableInt: \$nullVar, intList: \$mixedList, stringList: \$singleItem)")
            val rss = mkFactory().mkRequiredSelectionSets(
                variablesProvider = VariablesProviderInfo(setOf("nullVar", "mixedList", "singleItem")) {
                    MockVariablesProvider(
                        mapOf(
                            "nullVar" to null, // null for non-nullable type
                            "mixedList" to listOf(1, "invalid", 3), // mixed types in list
                            "singleItem" to 42 // single value where list expected
                        )
                    )
                },
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = emptyList(),
            ).objectSelections

            // All values pass through - validation happens at GraphQL execution level
            val result = rss!!.variablesResolvers.resolve(vresolveCtx)
            assertEquals(
                mapOf(
                    "nullVar" to null,
                    "mixedList" to listOf(1, "invalid", 3),
                    "singleItem" to 42
                ),
                result
            )
        }

    @Test
    fun `mkRequiredSelectionSets -- VariablesProvider throwing exception should propagate`(): Unit =
        runBlocking {
            val objectSelections = SelectionsParser.parse("Query", "foo(x: \$testVar)")
            val rss = mkFactory().mkRequiredSelectionSets(
                variablesProvider = VariablesProviderInfo(setOf("testVar")) {
                    ThrowingVariablesProvider(RuntimeException("Test exception from VariablesProvider"))
                },
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = emptyList(),
            ).objectSelections

            // The exception should be propagated up from the resolve call
            val thrownException = assertThrows<RuntimeException> {
                rss!!.variablesResolvers.resolve(vresolveCtx)
            }

            // Verify it's the same exception we threw
            assertEquals("Test exception from VariablesProvider", thrownException.message)
        }

    @Test
    fun `mkRequiredSelectionSets -- VariablesProvider returning undeclared variables should throw at request time`(): Unit =
        runBlocking {
            val objectSelections = SelectionsParser.parse("Query", "foo(x: \$declaredVar)")
            val rss = mkFactory().mkRequiredSelectionSets(
                variablesProvider = VariablesProviderInfo(setOf("declaredVar")) {
                    MockVariablesProvider(
                        mapOf(
                            "declaredVar" to 42,
                            "undeclaredVar" to "should not be allowed" // Extra variable not in variables set
                        )
                    )
                },
                objectSelections = objectSelections,
                querySelections = null,
                variablesProviderContextFactory = variablesProviderContextFactory,
                variables = emptyList(),
            ).objectSelections

            // Should throw IllegalStateException because VariablesProvider returns extra variables
            assertThrows<IllegalStateException> {
                rss!!.variablesResolvers.resolve(vresolveCtx)
            }
        }
}
