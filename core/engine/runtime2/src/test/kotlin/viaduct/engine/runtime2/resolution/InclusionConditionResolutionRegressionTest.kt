@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class InclusionConditionResolutionRegressionTest : ResolutionDispatcherResource {
    @Test
    fun `nested contradictory demand leaves the field excluded instead of publishing an error`() {
        assertExcluded { x, y, z ->
            InclusionCondition.requires(mapOf(x to true))
                .or(InclusionCondition.requires(mapOf(y to true)))
                .or(InclusionCondition.requires(mapOf(z to true)))
                .and(InclusionCondition.requires(mapOf(x to false)))
        }
    }

    @Test
    fun `duplicate guarded demand leaves the field excluded instead of publishing an error`() {
        assertExcluded { x, _, z ->
            val xRequired = InclusionCondition.requires(mapOf(x to true))
            val zRequired = InclusionCondition.requires(mapOf(z to true))
            zRequired.and(xRequired).or(xRequired).and(zRequired)
        }
    }

    private fun assertExcluded(condition: (Arguments.Variable, Arguments.Variable, Arguments.Variable) -> InclusionCondition) {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              dependency: Int @resolver(result: 7)
              healthy: Int @resolver(result: 42)
            }
            """.trimIndent(),
        )
        val dependency = world.schema.requireObjectField("Query", "dependency")
        val healthy = world.schema.requireObjectField("Query", "healthy")
        val queryType = world.schema.requireQueryTypeDef()
        val owner = ResolverOccurrenceId.at(ObjectEngineResult.of(queryType, mutable = true), emptyList())
        val x = Arguments.Variable.of(dependency, "x").instantiate(owner)
        val y = Arguments.Variable.of(dependency, "y").instantiate(owner)
        val z = Arguments.Variable.of(dependency, "z").instantiate(owner)
        val applications = java.util.Collections.synchronizedList(mutableListOf<String>())
        val observer = object : ResolverObserver {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                applications += observation.field.name
            }
        }
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        operation.variableBindings.bindVariable(requireNotNull(x.instanceId), VariableBinding.Error)
        operation.variableBindings.bindVariable(requireNotNull(y.instanceId), false)
        operation.variableBindings.bindVariable(requireNotNull(z.instanceId), false)
        val dependencyKey = ObjectEngineResult.GroundKey.of(dependency, emptyMap())
        val healthyKey = ObjectEngineResult.GroundKey.of(healthy, emptyMap())
        val result = operation.resolveWithTestDispatcher(
            selectionForestOf(
                Selection.of(dependencyKey, setOf(queryType), selectionForestOf(), condition(x, y, z)),
                Selection.of(healthyKey, setOf(queryType), selectionForestOf()),
            )
        )

        val dependencyCell = result.getCell(dependencyKey)
        assertFalse(runBlocking { dependencyCell.fetchActivated() })
        assertFalse(dependencyCell.value.isCompleted)
        assertEquals(listOf("healthy"), applications.toList())
        assertEquals(42, result.getCell(healthyKey).value.get())
    }
}
