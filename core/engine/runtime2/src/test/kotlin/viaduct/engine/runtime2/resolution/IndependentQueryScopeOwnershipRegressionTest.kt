package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.contract.registeredResolverOccurrenceApplicationIdentityCounts
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver07.resolve as resolve07
import viaduct.engine.runtime2.resolvers.resolver23.resolve as resolve23

/** An independent Query execution and its associated Query OER have distinct semantic roles. */
class IndependentQueryScopeOwnershipRegressionTest : ResolutionDispatcherResource {
    @Test
    fun `DFS reference Query input may itself contain shared Query owners`() {
        verify(7)
    }

    @Test
    fun `grounded coroutine reference Query input may itself contain shared Query owners`() {
        verify(23)
    }

    @Test
    fun `symbolic reference Query input may itself contain shared Query owners`() {
        verify(26)
    }

    private fun verify(resolver: Int) {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              first: Int! @resolver(script: "ref('target')")
              target: Int! @resolver(qf: "intermediate", script: "queryValue.intermediate")
              intermediate: Int! @resolver(qf: "source", script: "queryValue.source")
              source: Int! @resolver(script: "7")
            }
            """.trimIndent(),
        )
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolutionObserver = observer)
        val selections = world.schemas.fragmentFrom("fragment Test on Query { first }").subselections
        val result = when (resolver) {
            7 -> operation.resolve07(selections)
            23 -> operation.resolve23(selections)
            else -> operation.resolveWithTestDispatcher(selections)
        }
        val first = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "first"), emptyMap())
        assertEquals(7, result.getCell(first).value.get())
        assertTrue(result.correctResolution(operation, selections.merge(world.schema.requireQueryTypeDef())))
        assertEquals(2, observer.allQueryFragmentResults().values.flatten().toSet().size)
        assertEquals(4, result.registeredResolverOccurrenceApplicationIdentityCounts(operation).values.sum())
    }
}
