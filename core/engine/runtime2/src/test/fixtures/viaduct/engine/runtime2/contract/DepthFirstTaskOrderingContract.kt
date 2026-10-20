package viaduct.engine.runtime2.contract

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
    fun `finishes each recursive subtree before visiting its next sibling`() {
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

        val rootTask = ResolverTaskObservation.SlotOrchestration("Query", emptyList())
        val afterTask = ResolverTaskObservation.SlotResolver("after", emptyList())
        val validTraces = listOf(listOf("left", "right"), listOf("right", "left")).flatMap { children ->
            val containerSubtree = buildList {
                add(ResolverTaskObservation.SlotResolver("container", emptyList()))
                add(ResolverTaskObservation.SlotOrchestration("Container", listOf("container")))
                children.forEach { child ->
                    val path = listOf("container", child)
                    add(ResolverTaskObservation.SlotOrchestration("Child", path))
                    add(ResolverTaskObservation.SlotResolver("nested", path))
                }
            }
            listOf(
                listOf(rootTask, afterTask) + containerSubtree,
                listOf(rootTask) + containerSubtree + afterTask,
            )
        }

        assertTrue(taskTrace in validTraces) {
            "Expected complete depth-first subtrees in either sibling order, but got $taskTrace"
        }
    }
}
