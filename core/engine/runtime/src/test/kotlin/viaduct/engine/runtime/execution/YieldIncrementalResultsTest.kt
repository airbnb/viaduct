package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.ResultPath
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult

class YieldIncrementalResultsTest {
    @Test
    fun `without deferred tasks the response is complete`() =
        runTest {
            val data = mapOf("value" to null)
            val error = GraphQLError.newError().message("initial error").path(listOf("value")).build()

            val result = YieldIncrementalResults(mkExecutionParameters(), data, listOf(error), Work.empty)

            assertFalse(result is IncrementalExecutionResult)
            assertEquals(mapOf("data" to data, "errors" to listOf(error.toSpecification())), result.toSpecification())
        }

    @Test
    fun `initial pending groups lead to nested patches and completion`() =
        runTest {
            val parameters = mkExecutionParameters()
            val path = ResultPath.rootPath().segment("heroes").segment(1)
            val parent = DeferDeliveryGroup(ResultPath.rootPath(), "parent", null)
            val child = DeferDeliveryGroup(path, "child", parent)
            val nested = ExecutionGroupTask(setOf(child), path) {
                ExecutionGroupResult(mapOf("name" to "Leia"), emptyList(), Work.empty)
            }
            val initial = ExecutionGroupTask(setOf(parent), ResultPath.rootPath()) {
                ExecutionGroupResult(
                    mapOf("heroes" to listOf(null, emptyMap<String, Any?>())),
                    emptyList(),
                    Work(setOf(child), listOf(nested)),
                )
            }
            val data = mapOf("count" to 2)
            val error = GraphQLError.newError().message("initial error").build()

            val result = YieldIncrementalResults(parameters, data, listOf(error), Work(setOf(parent), listOf(initial))) as IncrementalExecutionResult

            assertEquals(data, result.getData<Map<String, Any?>>())
            result.errors.shouldContainExactly(error)
            result.pending.map { it.toSpecification() }.shouldContainExactly(mapOf("id" to "0", "path" to emptyList<Any>(), "label" to "parent"))
            assertTrue(result.hasNext)
            val updates = result.updates.toList()
            val patches = updates.flatMap { it.incremental }.map { it as IncrementalEntry.Object }
            updates.flatMap { it.pending }.map { it.path }.shouldContainExactly(path)
            updates.flatMap { it.completed }.map { it.id }.shouldContainExactly("0", "1")
            patches.map { it.data }.shouldContainExactly(mapOf("heroes" to listOf(null, emptyMap<String, Any?>())), mapOf("name" to "Leia"))
            patches.map { it.subPath }.shouldContainExactly(null, null)
            assertFalse(updates.last().hasNext)

            val root = DeferDeliveryGroup(ResultPath.rootPath(), "root", null)
            val atIndex = ExecutionGroupTask(setOf(root), path) {
                ExecutionGroupResult(mapOf("name" to "Leia"), emptyList(), Work.empty)
            }
            val withSubPath = YieldIncrementalResults(parameters, emptyMap(), emptyList(), Work(setOf(root), listOf(atIndex))) as IncrementalExecutionResult
            val patch = withSubPath.updates.toList().flatMap { it.incremental }.single() as IncrementalEntry.Object
            assertEquals(path, patch.subPath)
        }

    @Test
    fun `deferred tasks run before collection and retain their results`() =
        runTest {
            val group = DeferDeliveryGroup(ResultPath.rootPath(), "later", null)
            val finished = CompletableDeferred<Unit>()
            val task = ExecutionGroupTask(setOf(group), group.path) {
                finished.complete(Unit)
                ExecutionGroupResult(mapOf("value" to 1), emptyList(), Work.empty)
            }

            val result = YieldIncrementalResults(mkExecutionParameters(), emptyMap(), emptyList(), Work(setOf(group), listOf(task))) as IncrementalExecutionResult
            finished.await()

            val updates = result.updates.toList()
            val patch = updates.flatMap { it.incremental }.single() as IncrementalEntry.Object
            assertEquals(mapOf("value" to 1), patch.data)
            updates.flatMap { it.completed }.map { it.id }.shouldContainExactly(result.pending.single().id)
            assertFalse(updates.last().hasNext)
        }

    @Test
    fun `cancelling an uncollected result stops its deferred tasks`() =
        runTest {
            val group = DeferDeliveryGroup(ResultPath.rootPath(), "later", null)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val task = ExecutionGroupTask(setOf(group), group.path) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            val result = YieldIncrementalResults(mkExecutionParameters(), emptyMap(), emptyList(), Work(setOf(group), listOf(task))) as IncrementalExecutionResult
            started.await()

            result.cancel()

            stopped.await()
            assertThrows<CancellationException> { result.updates.toList() }
        }
}
