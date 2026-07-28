@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.language.Field as GJField
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.QueryPlanExecutionCondition.Companion.ALWAYS_EXECUTE
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.FlagManager

/**
 * Unit tests for FieldResolver's ExecutionCondition handling.
 *
 * These tests verify that FieldResolver.resolveField() properly evaluates ExecutionConditions
 * on child QueryPlans before launching them.
 */
class FieldResolverExecutionConditionTest {
    companion object {
        private val schemaSDL = """
            extend type Query {
                parent: Parent!
            }
            type Parent {
                x: Int
                y: Int
            }
        """.trimIndent()
    }

    @Test
    fun `FieldResolver throws when child plan origin does not match runtime field`() {
        val runtimeType = GraphQLObjectType.newObject()
            .name("HiveTable")
            .field { it.name("id").type(graphql.Scalars.GraphQLID) }
            .build()
        val schema = GraphQLSchema.newSchema().query(runtimeType).build()
        val engineExecutionContext = ContextMocks(
            myFullSchema = viaduct.engine.api.ViaductSchema(schema),
            myFlagManager = FlagManager.Default,
        ).engineExecutionContext
        val field = GJField.newField("id").build()
        val childRss = createRSS("HiveTable", "id")
        val childSelectionSet = QueryPlan.SelectionSet.empty(runtimeType)
        val childFragments = QueryPlan.Fragments.empty
        val childPlan = QueryPlan(
            selectionSet = childSelectionSet,
            fragments = childFragments,
            variablesResolvers = emptyList(),
            childPlanIds = emptyList(),
            baseIndex = QueryPlanIndex.empty(),
            attribution = null,
            executionCondition = ALWAYS_EXECUTE,
            variableDefinitions = emptyList(),
            requiredSelectionSetId = childRss.id,
        )
        val collectedField = QueryPlan.CollectedField(
            responseKey = "id",
            selectionSet = null,
            mergedField = MergedField.newMergedField().addField(field).build(),
            childPlans = listOf(FieldChildPlan(childPlan, "OtherNode" to "id")),
            fieldTypeChildPlans = FieldTypeChildPlans.empty,
        )
        val parameters = mockk<ExecutionParameters>()
        val executionStepInfo = mockk<ExecutionStepInfo>()
        every { executionStepInfo.objectType } returns runtimeType
        every { parameters.engineExecutionContext } returns engineExecutionContext
        every { parameters.currentObjectEngineResult } returns ObjectEngineResultImpl.newForType(runtimeType)
        every { parameters.executionStepInfo } returns executionStepInfo

        val exception = assertThrows<IllegalStateException> {
            FieldResolver(
                AccessCheckRunner(DefaultCoroutineInterop),
                DefaultCoroutineInterop,
            ).resolveField(parameters, collectedField)
        }

        assertTrue(exception.message!!.contains("OtherNode"))
        assertTrue(exception.message!!.contains("HiveTable.id"))
    }

    @Test
    fun `FieldResolver launches child QueryPlan when ExecutionCondition returns true`() {
        // Track whether the child resolver was called
        val childResolverCallCount = AtomicInteger(0)

        val bootstrapper = EngineTestModule(schemaSDL) {
            field("Query" to "parent") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Parent"),
                            mapOf("x" to 1)
                        )
                    }
                }
            }

            // Child field resolver that tracks invocations
            field("Parent" to "y") {
                resolver {
                    objectSelections("x")
                    fn { selectors, _ ->
                        childResolverCallCount.incrementAndGet()
                        selectors.associateWith { selector ->
                            Result.success((selector.syncObjectValueGetter().get("x") as Int) + 1)
                        }
                    }
                }
            }
        }

        bootstrapper.runFeatureTest {
            // Execute query - child resolver should be called since ExecutionCondition defaults to ALWAYS_EXECUTE
            val result = runQuery("{ parent { x y } }")

            result.assertJson("""{"data": {"parent": { "x": 1, "y": 2 }}}""")

            // Verify the child resolver was actually invoked
            assert(childResolverCallCount.get() > 0) {
                "Expected child resolver to be called when ExecutionCondition returns true, but it was called ${childResolverCallCount.get()} times"
            }
        }
    }
}
