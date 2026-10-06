package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.contract.registeredResolverOccurrenceApplicationIdentityCounts
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver02.resolve as resolve02
import viaduct.engine.runtime2.resolvers.resolver23.resolve as resolve23

/** An independent Query execution and its associated Query OER have distinct semantic roles. */
class IndependentQueryScopeOwnershipRegressionTest : ResolutionDispatcherResource {
    @Test
    fun `DFS reference Query input may itself contain shared Query owners`() = verify(2)

    @Test
    fun `grounded coroutine reference Query input may itself contain shared Query owners`() = verify(23)

    @Test
    fun `symbolic reference Query input may itself contain shared Query owners`() = verify(26)

    private fun verify(resolver: Int) {
        val world = TestWorld.fromSDL(
            selectiveResolvers = resolver != 2,
            schemaSDL = """
                type Query { first: Int!, target: Int!, intermediate: Int!, source: Int! }
            """.trimIndent(),
            fieldResolvers = { schema ->
                val empty = schema.loweredSchema.emptyFragmentOf("Query")
                val target = schema.loweredSchema.requireObjectField("Query", "target")
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "first") to
                        fieldResolverOf(empty) { _, _ -> RootFieldReferenceData.of(listOf(target), emptyMap()) },
                    target to fieldResolverOf(
                        empty,
                        schema.fragmentFrom("fragment TargetInput on Query { intermediate }"),
                    ) { _, query, _ -> query.outputValue("intermediate") },
                    schema.loweredSchema.requireObjectField("Query", "intermediate") to fieldResolverOf(
                        empty,
                        schema.fragmentFrom("fragment IntermediateInput on Query { source }"),
                    ) { _, query, _ -> query.outputValue("source") },
                    schema.loweredSchema.requireObjectField("Query", "source") to fieldResolverOf(empty) { _, _ -> 7 },
                )
            },
        )
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val selections = world.schemas.fragmentFrom("fragment Test on Query { first }").subselections
        val result = when (resolver) {
            2 -> operation.resolve02(selections)
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
