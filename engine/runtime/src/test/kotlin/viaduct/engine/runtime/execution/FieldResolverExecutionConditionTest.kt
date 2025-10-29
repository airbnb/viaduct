@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.graphql.test.assertJson

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
    fun `FieldResolver launches child QueryPlan when ExecutionCondition returns true`() {
        // Track whether the child resolver was called
        val childResolverCallCount = AtomicInteger(0)

        val bootstrapper = MockTenantModuleBootstrapper(schemaSDL) {
            field("Query" to "parent") {
                resolver {
                    fn { _, _, _, _, _ ->
                        mkEngineObjectData(
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
                            Result.success((selector.objectValue.fetch("x") as Int) + 1)
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
