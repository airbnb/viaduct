package semantics.resolver26

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.ObjectEngineResult
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.outputValue
import model.registry.fieldResolverOf
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.resolvers.resolver02.resolve as resolve02
import semantics.resolvers.resolver23.resolve as resolve23
import semantics.shared.SharedOperationContext

/** An independent Query execution and its associated Query OER have distinct semantic roles. */
class IndependentQueryScopeOwnershipRegressionTest : Resolver26DispatcherResource {
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
