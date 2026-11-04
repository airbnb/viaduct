@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

@OptIn(ExperimentalCoroutinesApi::class)
class MutationOrchestrationTaskTest : ResolutionDispatcherResource {
    @Test
    fun `mutation fields retain their namespace task and nested namespace creator`() {
        val tasks = ConcurrentHashMap<String, FieldResolverTask>()
        val fixture = TestWorld.fromSDL(
            """
            directive @namespaceType on OBJECT
            type Query { idle: Int }
            type Mutation { update: Int group: Group }
            type Group @namespaceType { update: Int }
            """.trimIndent(),
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                listOf("Mutation", "Group").associate { type ->
                    schema.requireObjectField(type, "update") to fieldResolverOf(schema.emptyFragmentOf(type)) { _, _, ctx ->
                        tasks[type] = assertIs<FieldResolverTask>(ctx)
                        7
                    }
                }
            },
        )
        val result = SharedOperationContext.create(fixture.assumptions).resolveWithTestDispatcher(
            fixture.schemas.operationSelectionsFrom("mutation { update group { update } }"),
        )
        assertTrue(result.keys.all { result.getCell(it).value.isCompleted })
        val root = assertIs<MutationOrchestrationTask<*>>(tasks.getValue("Mutation").creator)
        val group = assertIs<MutationOrchestrationTask<*>>(tasks.getValue("Group").creator)
        assertNull(root.creator)
        assertSame(root, group.creator)
        assertSame(result, root.namespace.target)
        assertEquals("Group", group.namespace.target.type.name)
        tasks.values.forEach { assertSame(it, it.publication.task) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancellation before orchestration entry terminates every prepared cell`(precomputed: Boolean) =
        runTest {
            var calls = 0
            val fixture = fixture { ++calls }
            val job = Job()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
            val result = SharedOperationContext.create(fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)).startResolve(
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `suspended mutation prevents its successor from starting and cancels locally`(precomputed: Boolean) =
        runTest {
            var calls = 0
            val fixture = fixture {
                ++calls
                awaitCancellation()
            }
            val job = Job()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
            val result = SharedOperationContext.create(fixture.newAssumptions(usePrecomputedConstructionDemand = precomputed)).startResolve(
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

    @Test
    fun `the next mutation waits for child work after the first value is published`() =
        runTest {
            val releaseFirstMutationChild = CompletableDeferred<Unit>()
            val effects = mutableListOf<String>()
            val world = orderedScalarMutations(
                resolveFirst = { firstMutationScope ->
                    effects += "first"
                    // This child belongs to the first resolver and outlives publication of its scalar value.
                    firstMutationScope.launch { releaseFirstMutationChild.await() }
                    1
                },
                resolveSecond = {
                    effects += "second"
                    2
                },
            )
            val resolution = SharedOperationContext.create(world.assumptions).startResolution(
                world.schemas.operationSelectionsFrom("mutation { first second }"),
                backgroundScope,
            )
            val first = resolution.root.keys.single { it.field.name == "first" }
            val second = resolution.root.keys.single { it.field.name == "second" }
            runCurrent()
            assertEquals(1, resolution.root.getCell(first).value.get())
            assertEquals(listOf("first"), effects)
            assertFalse(resolution.root.getCell(second).value.isCompleted)

            releaseFirstMutationChild.complete(Unit)
            resolution.await()

            assertEquals(listOf("first", "second"), effects)
            assertEquals(2, resolution.root.getCell(second).value.get())
        }

    private fun orderedScalarMutations(
        resolveFirst: suspend (CoroutineScope) -> Int,
        resolveSecond: suspend () -> Int,
    ): TestWorld =
        TestWorld.fromSDL(
            "type Query { idle: Int } type Mutation { first: Int second: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Mutation", "first") to fieldResolverOf(schema.emptyFragmentOf("Mutation")) { _, _, context ->
                        resolveFirst(assertIs<FieldResolverTask>(context).fieldTaskScope)
                    },
                    schema.requireObjectField("Mutation", "second") to fieldResolverOf(schema.emptyFragmentOf("Mutation")) { _, _ ->
                        resolveSecond()
                    },
                )
            },
        )

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
