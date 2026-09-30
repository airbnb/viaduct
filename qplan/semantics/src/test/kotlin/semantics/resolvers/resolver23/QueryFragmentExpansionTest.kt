package semantics.resolvers.resolver23

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import model.ObjectEngineResult
import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.parsing.operationSelectionsFrom
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.contract.CoroutineResolverTestSubject
import semantics.resolvers.resolver21.startCoroutineResolution
import semantics.resolvers.successorDemandFromConstructionDemand
import semantics.shared.CycleCheckState
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult

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
            TestWorld.fromSDL(
                schemaSDL =
                    "type Query { " +
                        (0..depth).joinToString(" ") { index -> "field$index: Int!" } +
                        " }",
                selectiveResolvers = true,
                fieldResolvers = { schema ->
                    (0..depth).associate { index ->
                        val field = schema.loweredSchema.requireObjectField("Query", "field$index")
                        val queryFragment =
                            if (index == depth) {
                                schema.loweredSchema.emptyFragmentOf("Query")
                            } else {
                                schema.fragmentFrom(
                                    "fragment Input on Query { field${index + 1} }",
                                )
                            }
                        field to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment,
                            ) { _, _, _ ->
                                resolverInvocations.incrementAndGet()
                                1
                            }
                    }
                },
                fieldCheckers = { schema ->
                    (0..depth).associate { index ->
                        val field = schema.loweredSchema.requireObjectField("Query", "field$index")
                        val querySelections =
                            if (index == depth) {
                                materializeSelectionForestOf()
                            } else {
                                schema
                                    .fragmentFrom(
                                        "fragment Input on Query { field${index + 1} }",
                                    ).materializeSelections
                            }
                        val templates =
                            ResolverFragmentTemplates(
                                objectFragmentTemplate = materializeSelectionForestOf(),
                                queryFragmentTemplate = querySelections,
                            )
                        field to
                            FieldCheckerResolver.of(
                                field = field,
                                queryType = schema.loweredSchema.requireQueryTypeDef(),
                                fragmentTemplates = mapOf("left" to templates, "right" to templates),
                            ) { _, _, _ ->
                                checkerInvocations.incrementAndGet()
                                CheckerResult.Success
                            }
                    }
                },
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
