package semantics.contract

import kotlin.test.assertEquals
import model.ObjectEngineResult
import model.SelectionForest
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.requireField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import org.junit.jupiter.api.Test
import semantics.shared.SharedOperationContext
import viaduct.engine.api.EngineObjectData

sealed interface ResolverTaskObservation {
    val path: List<String>

    data class SlotOrchestration(
        val objectType: String,
        override val path: List<String>,
    ) : ResolverTaskObservation

    data class SlotResolver(
        val fieldName: String,
        override val path: List<String>,
    ) : ResolverTaskObservation
}

/**
 * Contract for queue-backed resolvers that reproduce recursive depth-first task ordering.
 */
interface DepthFirstTaskOrderingContract : ResolverContract {
    fun resolveAndObserveTasks(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
        taskObserver: (ResolverTaskObservation) -> Unit,
    ): ObjectEngineResult

    @Test
    fun `executes the exact recursive task order across equal-depth siblings`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Child { nested: String! }
                    type Container {
                      left: Child!
                      right: Child!
                    }
                    type Query {
                      container: Container!
                      after: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "container") to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                schema.loweredSchema.objectOf("Container") {
                                    "left" setTo objectOf("Child")
                                    "right" setTo objectOf("Child")
                                }
                            },
                        schema.loweredSchema.requireField("Child", "nested") to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Child"),
                            ) { _, _ ->
                                "nested"
                            },
                        schema.loweredSchema.requireField("Query", "after") to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                "after"
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val selections =
            testWorld.schemas.fragmentFrom(
                """
                    fragment ignored on Query {
                      container {
                        left { nested }
                        right { nested }
                      }
                      after
                    }
                """.trimIndent(),
            ).subselections
        val taskTrace = mutableListOf<ResolverTaskObservation>()

        resolveAndObserveTasks(
            operation = SharedOperationContext.create(world),
            root = world.objectOf("Query"),
            selections = selections,
            taskObserver = taskTrace::add,
        )

        assertEquals(
            listOf(
                ResolverTaskObservation.SlotOrchestration("Query", emptyList()),
                ResolverTaskObservation.SlotResolver("container", emptyList()),
                ResolverTaskObservation.SlotOrchestration(
                    "Container",
                    listOf("container"),
                ),
                ResolverTaskObservation.SlotOrchestration(
                    "Child",
                    listOf("container", "left"),
                ),
                ResolverTaskObservation.SlotResolver(
                    "nested",
                    listOf("container", "left"),
                ),
                ResolverTaskObservation.SlotOrchestration(
                    "Child",
                    listOf("container", "right"),
                ),
                ResolverTaskObservation.SlotResolver(
                    "nested",
                    listOf("container", "right"),
                ),
                ResolverTaskObservation.SlotResolver("after", emptyList()),
            ),
            taskTrace,
        )
    }
}
