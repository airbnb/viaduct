package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.GraphqlErrorException
import graphql.execution.ResultPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.PendingEntry

@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalExecutionResultImplTest {
    @Test
    fun `initial result retains graphql java data errors and extensions`() {
        val data = mapOf("name" to null)
        val error = GraphQLError.newError().message("name failed").build()
        val initial = ExecutionResult.newExecutionResult()
            .data(data)
            .errors(listOf(error))
            .extensions(mapOf("trace" to "123"))
            .build()
        val pending = listOf(PendingEntry("0", ResultPath.rootPath(), "details"))
        val result: ExecutionResult = IncrementalExecutionResultImpl(initial, pending, true, emptyFlow())

        assertSame(data, result.getData<Map<String, Any?>>())
        assertSame(initial.errors, result.errors)
        assertSame(initial.extensions, result.extensions)
        assertTrue(result.isDataPresent)
        assertEquals(
            initial.toSpecification() + mapOf("pending" to listOf(mapOf("id" to "0", "path" to emptyList<Any>(), "label" to "details")), "hasNext" to true),
            result.toSpecification(),
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `initial result distinguishes absent data from explicit null`(dataPresent: Boolean) {
        val builder = ExecutionResult.newExecutionResult()
        if (dataPresent) builder.data(null)
        val result: ExecutionResult = IncrementalExecutionResultImpl(builder.build(), emptyList(), false, emptyFlow())

        assertNull(result.getData<Any?>())
        assertEquals(dataPresent, result.isDataPresent)
        assertEquals(dataPresent, result.toSpecification().containsKey("data"))
        assertEquals(false, result.toSpecification()["hasNext"])
        assertEquals(emptyList<Any>(), result.toSpecification()["pending"])
    }

    @Test
    fun `instrumentation transforms initial fields while retaining deferred execution`() =
        runTest {
            val initial = ExecutionResult.newExecutionResult().data(mapOf("value" to 1)).build()
            val pending = listOf(PendingEntry("0", ResultPath.rootPath(), null))
            val updates = flowOf(GetIncrementalStreamUpdateResult(false, emptyList(), emptyList(), emptyList()))
            var cancellations = 0
            val original: IncrementalExecutionResult = IncrementalExecutionResultImpl(initial, pending, true, updates) { cancellations++ }
            val error = GraphQLError.newError().message("instrumented").build()

            val transformed: IncrementalExecutionResult = original.transform {
                it.data(mapOf("value" to 2)).errors(listOf(error)).extensions(mapOf("instrumented" to true))
            }

            assertEquals(mapOf("value" to 1), original.getData<Map<String, Any?>>())
            assertSame(original.pending, transformed.pending)
            assertSame(original.updates, transformed.updates)
            assertTrue(transformed.hasNext)
            assertEquals(
                mapOf(
                    "data" to mapOf("value" to 2),
                    "errors" to listOf(error.toSpecification()),
                    "extensions" to mapOf("instrumented" to true),
                    "pending" to listOf(mapOf("id" to "0", "path" to emptyList<Any>())),
                    "hasNext" to true,
                ),
                transformed.toSpecification(),
            )
            assertEquals(mapOf("hasNext" to false), transformed.updates.toList().single().toSpecification())
            transformed.cancel()
            assertEquals(1, cancellations)
            original.cancel()
            assertEquals(2, cancellations)
        }

    @Test
    fun `real deferred work announces nested groups and reports failures`() =
        runTest {
            val path = ResultPath.rootPath().segment("hero")
            val parent = DeferDeliveryGroup(path, "parent", null)
            val child = DeferDeliveryGroup(path.segment("details"), "child", parent)
            val failure = GraphqlErrorException.newErrorException().message("child failed").build()
            val nested = ExecutionGroupTask(setOf(child), child.path) { throw failure }
            val initial = ExecutionGroupTask(setOf(parent), parent.path) {
                ExecutionGroupResult(mapOf("name" to "Ada"), emptyList(), Work(setOf(child), listOf(nested)))
            }
            val result = incrementalResult(Work(setOf(parent), listOf(initial)))

            assertEquals(
                mapOf(
                    "data" to emptyMap<String, Any?>(),
                    "pending" to listOf(mapOf("id" to "0", "path" to listOf("hero"), "label" to "parent")),
                    "hasNext" to true,
                ),
                result.toSpecification(),
            )
            assertEquals(
                listOf(
                    mapOf(
                        "hasNext" to true,
                        "incremental" to listOf(mapOf("id" to "0", "data" to mapOf("name" to "Ada"))),
                        "completed" to listOf(mapOf("id" to "0")),
                        "pending" to listOf(mapOf("id" to "1", "path" to listOf("hero", "details"), "label" to "child")),
                    ),
                    mapOf(
                        "hasNext" to false,
                        "completed" to listOf(mapOf("id" to "1", "errors" to listOf(failure.toSpecification()))),
                    ),
                ),
                result.updates.toList().map { it.toSpecification() },
            )
        }

    @Test
    fun `cancelling a transformed result stops deferred tasks before collection`() =
        runTest {
            val group = DeferDeliveryGroup(ResultPath.rootPath(), "deferred", null)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val task = ExecutionGroupTask(setOf(group), group.path) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val result = incrementalResult(Work(setOf(group), listOf(task))).transform {
                it.extensions(mapOf("instrumented" to true))
            }
            started.await()

            result.cancel()
            runCurrent()

            assertTrue(cancelled.isCompleted)
            assertTrue(coroutineContext[Job]!!.isActive)
            assertThrows<CancellationException> { result.updates.collect() }
        }

    @Test
    fun `stopping response collection cancels remaining deferred tasks`() =
        runTest {
            val path = ResultPath.rootPath()
            val quickGroup = DeferDeliveryGroup(path, "quick", null)
            val hangingGroup = DeferDeliveryGroup(path, "hanging", null)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val quick = ExecutionGroupTask(setOf(quickGroup), path) {
                started.await()
                ExecutionGroupResult(mapOf("value" to 1), emptyList(), Work.empty)
            }
            val hanging = ExecutionGroupTask(setOf(hangingGroup), path) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val result = incrementalResult(Work(setOf(quickGroup, hangingGroup), listOf(quick, hanging)))

            val update = result.updates.take(1).toList().single()
            runCurrent()

            assertEquals(
                mapOf(
                    "hasNext" to true,
                    "incremental" to listOf(mapOf("id" to "0", "data" to mapOf("value" to 1))),
                    "completed" to listOf(mapOf("id" to "0")),
                ),
                update.toSpecification(),
            )
            assertTrue(cancelled.isCompleted)
            assertTrue(coroutineContext[Job]!!.isActive)
        }

    private fun CoroutineScope.incrementalResult(work: Work): IncrementalExecutionResult {
        val ctx = mkExecutionParameters()
        val queue = CreateWorkQueue(work, scope = this)
        return IncrementalExecutionResultImpl(
            initialResult = ExecutionResult.newExecutionResult().data(emptyMap<String, Any?>()).build(),
            pending = GetPendingEntries(ctx, queue.initialGroups),
            hasNext = queue.initialGroups.isNotEmpty(),
            updates = MapIncrementalWorkEventsToResponseEvent(ctx, queue.events),
            cancelExecution = queue::cancel,
        )
    }
}
