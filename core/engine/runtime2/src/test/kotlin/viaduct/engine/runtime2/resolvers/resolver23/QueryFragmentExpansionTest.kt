package viaduct.engine.runtime2.resolvers.resolver23

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import viaduct.engine.runtime2.contract.CoroutineResolverTestSubject
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver21.startCoroutineResolution
import viaduct.engine.runtime2.resolvers.successorDemandFromConstructionDemand
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class QueryFragmentExpansionTest : CoroutineResolverTestSubject() {
    override fun startResolution(
        operation: SharedOperationContext<*>,
        requestScope: CoroutineScope,
        selections: SelectionForest,
        cycleChecker: CycleCheckState,
    ): ObjectEngineResult =
        startCoroutineResolution(
            operation,
            requestScope,
            selections,
            cycleChecker,
            selectiveResolvers = true,
            complete = { demand, possibleRootTypes ->
                demand.successorDemandFromConstructionDemand(operation, possibleRootTypes)
            },
        )

    @Test
    fun `resolver and checker Query fragments coalesce in one associated Query scope`() {
        val depth = 24
        val invocations = resolveQueryFragmentChain(depth)

        assertEquals(depth + 1, invocations.resolverCount)
        assertEquals(depth + 1, invocations.checkerCount)
    }

    private fun resolveQueryFragmentChain(depth: Int): InvocationCounts {
        val resolverInvocations = AtomicInteger()
        val checkerInvocations = AtomicInteger()
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL = "extend type Query {\n" + (0..depth).joinToString("\n") { index ->
                    val required = if (index == depth) "" else "field${index + 1}"
                    """
                    field$index: Int!
                      @resolver(qf: "$required", script: "resolverInvocations.incrementAndGet(); 1")
                      @checker(
                        inputs: [{name: "left", qf: "$required"}, {name: "right", qf: "$required"}]
                        script: "checkerInvocations.incrementAndGet(); allow"
                      )
                    """.trimIndent()
                } + "\n}",
                scriptBindings = mapOf("resolverInvocations" to resolverInvocations, "checkerInvocations" to checkerInvocations),
            )
        val world = testWorld.assumptions

        resolve(
            SharedOperationContext.create(world),
            testWorld.schemas.operationSelectionsFrom("{ field0 }"),
        )

        return InvocationCounts(
            resolverCount = resolverInvocations.get(),
            checkerCount = checkerInvocations.get(),
        )
    }

    private data class InvocationCounts(
        val resolverCount: Int,
        val checkerCount: Int,
    )
}
