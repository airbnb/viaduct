@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

@OptIn(ExperimentalCoroutinesApi::class)
class MutationOrchestrationTaskTest {
    @Test
    fun `cancellation before orchestration entry terminates every prepared cell`() =
        runTest {
            var calls = 0
            val fixture = fixture { ++calls }
            val job = Job()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
            val result = SharedOperationContext.create(fixture.assumptions).startResolve(
                fixture.schemas.operationSelectionsFrom("mutation { first: update second: update }"),
                scope,
            )
            assertEquals(2, result.keys.size)
            assertTrue(result.keys.all { !result.getCell(it).value.isCompleted })
            job.cancel()
            runCurrent()
            assertEquals(0, calls)
            result.keys.forEach { key ->
                val cell = result.getCell(key)
                assertTrue(cell.value.isCompleted)
                assertTrue(cell.fieldCheckerResult.isCompleted)
                assertThrows<CancellationException> { cell.value.get() }
            }
        }

    @Test
    fun `suspended mutation prevents its successor from starting and cancels locally`() =
        runTest {
            var calls = 0
            val fixture = fixture {
                ++calls
                awaitCancellation()
            }
            val job = Job()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
            val result = SharedOperationContext.create(fixture.assumptions).startResolve(
                fixture.schemas.operationSelectionsFrom("mutation { first: update second: update }"),
                scope,
            )
            runCurrent()
            assertEquals(1, calls)
            job.cancel()
            runCurrent()
            assertEquals(1, calls)
            assertTrue(result.keys.all { result.getCell(it).value.isCompleted && result.getCell(it).fieldCheckerResult.isCompleted })
        }

    private fun fixture(update: suspend () -> Int): TestWorld =
        TestWorld.fromSDL(
            "type Query { idle: Int } type Mutation { update: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Query", "idle") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 0 },
                    schema.requireObjectField("Mutation", "update") to fieldResolverOf(schema.emptyFragmentOf("Mutation")) { _, _ -> update() },
                )
            },
        )
}
