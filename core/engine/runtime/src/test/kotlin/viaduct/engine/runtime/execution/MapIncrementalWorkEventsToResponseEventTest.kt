package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.ResultPath
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MapIncrementalWorkEventsToResponseEventTest {
    @Test
    fun `batches combine values completions new groups and termination`() =
        runTest {
            val ctx = mkExecutionParameters()
            val path = ResultPath.rootPath().segment("users")
            val parent = DeferDeliveryGroup(path, "parent", null)
            val child = DeferDeliveryGroup(path.segment(1), "child", parent)
            val failure = DeferDeliveryGroup(path.segment(2), "failed", null)
            val fieldError = error("field failed")
            val groupError = error("group failed")
            GetPendingEntries(ctx, listOf(parent, failure))
            val events = flowOf(
                listOf(
                    WorkEvent.GroupValues(
                        parent,
                        listOf(
                            GroupValue(path, mapOf("name" to "Ada"), emptyList()),
                            GroupValue(path.segment(0), mapOf("name" to null), listOf(fieldError)),
                        )
                    ),
                    WorkEvent.GroupSuccess(parent, listOf(child)),
                ),
                listOf(WorkEvent.GroupFailure(failure, listOf(groupError)), WorkEvent.Terminated),
            )

            val updates = MapIncrementalWorkEventsToResponseEvent(ctx, events).toList()

            assertEquals(
                listOf(
                    mapOf(
                        "hasNext" to true,
                        "incremental" to listOf(
                            mapOf("id" to "0", "data" to mapOf("name" to "Ada")),
                            mapOf("id" to "0", "data" to mapOf("name" to null), "subPath" to listOf(0), "errors" to listOf(fieldError.toSpecification())),
                        ),
                        "completed" to listOf(mapOf("id" to "0")),
                        "pending" to listOf(mapOf("id" to "2", "path" to listOf("users", 1), "label" to "child")),
                    ),
                    mapOf(
                        "hasNext" to false,
                        "completed" to listOf(mapOf("id" to "1", "errors" to listOf(groupError.toSpecification()))),
                    ),
                ),
                updates.map { it.toSpecification() },
            )
        }

    @Test
    fun `termination without group events omits empty collections`() =
        runTest {
            val updates = MapIncrementalWorkEventsToResponseEvent(
                mkExecutionParameters(),
                flowOf(listOf(WorkEvent.Terminated)),
            ).toList()

            assertEquals(mapOf("hasNext" to false), updates.single().toSpecification())
        }

    @Test
    fun `producer failures reach the response reader`() =
        runTest {
            val failure = IllegalStateException("producer failed")
            val events = flow<List<WorkEvent>> { throw failure }

            assertSame(
                failure,
                assertThrows<IllegalStateException> {
                    MapIncrementalWorkEventsToResponseEvent(mkExecutionParameters(), events).toList()
                },
            )
        }

    private fun error(message: String): GraphQLError = GraphQLError.newError().message(message).build()
}
