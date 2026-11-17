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
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolutionTask
import viaduct.engine.runtime2.resolution.framework.ResolutionTaskState
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.SharedTaskDispatcher
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

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
            fixture.schemas.operationSelectionsFrom("mutation { update nested: group { update } }"),
        )
        assertTrue(result.keys.all { result.getCell(it).value.isCompleted })
        val root = assertIs<MutationOrchestrationTask<*>>(tasks.getValue("Mutation").creator)
        val group = assertIs<MutationOrchestrationTask<*>>(tasks.getValue("Group").creator)
        assertNull(root.creator)
        assertSame(root, group.creator)
        assertSame(result, root.occurrence.target)
        assertEquals("Group", group.occurrence.target.type.name)
        assertSame(result, group.occurrence.root)
        assertSame(root.occurrence, group.occurrence.parent)
        assertEquals(listOf(result.keys.single { it.field.name == "group" }), group.occurrence.path)
        tasks.values.forEach { assertSame(it, it.publication.task) }
    }

    @Test
    fun `throwing mutation publishes its error on an activated cell and preserves the next effect`() {
        val cause = IllegalStateException("mutation failed")
        var calls = 0
        val fixture = TestWorld.fromSDL(
            "type Query { idle: Int } type Mutation { update: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Mutation", "update") to fieldResolverOf(schema.emptyFragmentOf("Mutation")) { _, _, context ->
                        val publication = assertIs<FieldResolverTask>(context).publication
                        assertTrue(publication.checkerScheduled)
                        publication.publicationCell.checkActivated()
                        assertNull(publication.publicationCell.fieldCheckerResult.get())
                        if (++calls == 1) throw cause
                        calls
                    },
                )
            },
        )
        val result = SharedOperationContext.create(fixture.assumptions).resolveWithTestDispatcher(
            fixture.schemas.operationSelectionsFrom("mutation { first: update last: update }"),
        )
        val cells = result.keys.associate { key ->
            assertIs<ObjectEngineResult.MutationKey>(key).responseKey to result.getCell(key)
        }

        assertEquals(2, calls)
        assertTrue(result.isCompleted)
        assertSame(cause, assertIs<ErrorEngineResult>(cells.getValue("first").value.get()).errorData.cause)
        assertEquals(2, cells.getValue("last").value.get())
        cells.values.forEach { cell ->
            cell.checkActivated()
            assertNull(cell.fieldCheckerResult.get())
        }
    }

    @Test
    fun `production mutation publication rejects a cell that orchestration has not activated`(): Unit =
        runTest {
            var calls = 0
            val fixture = fixture { ++calls }
            val selections = assertIs<MutationSelectionForest>(fixture.schemas.operationSelectionsFrom("mutation { update }"))
            val selection = selections.single()
            val result = MutationObjectEngineResult.of(selections)
            val key = ObjectEngineResult.MutationKey.of(selection.key, selection.responseKey)
            val operation = OperationContext.create(SharedOperationContext.create(fixture.assumptions), this)
            val publication = FieldResolverTask.prepareMutation(
                operation,
                OEROccurrence(result, emptyList(), result),
                ObjectSelection.of(key, selection.possibleTypes, selection.subselections),
            )
            result.freeze()

            val failure = assertThrows<IllegalStateException> {
                FieldResolutionLogic(publication.task).publishResult()
            }

            assertEquals("Cell is not activated", failure.message)
            assertEquals(0, calls)
            assertFalse(publication.publicationCell.value.isCompleted)
            assertFalse(publication.publicationCell.fieldCheckerResult.isCompleted)
        }

    @Test
    fun `root creation rejects a mutation namespace forest`() {
        val fixture = TestWorld.fromSDL(
            """
            directive @namespaceType on OBJECT
            type Query { idle: Int }
            type Mutation { group: Group }
            type Group @namespaceType { update: Int }
            """.trimIndent(),
        )
        val selections = MutationSelectionForest.of(fixture.schema, fixture.schema.requireType("Group") as ViaductSchema.Object, emptyList())

        val failure = assertThrows<IllegalArgumentException> {
            MutationOrchestrationTask.createRoot<Unit>(
                operation = SharedOperationContext.create(fixture.assumptions),
                selections = selections,
                prepareField = { _, _, _ -> error("Unexpected field preparation") },
            )
        }

        assertEquals("Mutation execution must start at the Mutation root", failure.message)
    }

    @Test
    fun `orchestration activates each mutation member before handing off its work`(): Unit =
        runTest {
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
                        schema.requireObjectField(type, "update") to fieldResolverOf(schema.emptyFragmentOf(type)) { _, _ -> 7 }
                    }
                },
            )
            val selections = assertIs<MutationSelectionForest>(
                fixture.schemas.operationSelectionsFrom("mutation { first: update group { nested: update } last: update }"),
            )
            val result = MutationObjectEngineResult.of(selections)
            val lastKey = ObjectEngineResult.MutationKey.of(selections.orderedSelections().last().key, "last")
            val dispatched = mutableListOf<String>()
            val dispatcher = object : SharedTaskDispatcher<Nothing, Nothing, ObjectEngineResult.MutationKey> {
                override fun dispatchOrchestration(task: Nothing): Unit = error("Unexpected ordinary orchestration")

                override fun dispatchFieldResolver(publication: Nothing): Unit = error("Unexpected ordinary field")

                override suspend fun dispatchMutationField(
                    publication: ObjectEngineResult.MutationKey,
                    cell: EngineResultCell
                ) {
                    cell.checkActivated()
                    assertNull(cell.fieldCheckerResult.get())
                    if (publication != lastKey) {
                        assertThrows<IllegalStateException> { result.getCell(lastKey).checkActivated() }
                        assertFalse(result.getCell(lastKey).fieldCheckerResult.isCompleted)
                    }
                    dispatched += publication.responseKey
                    assertTrue(cell.value.complete(dispatched.size))
                }
            }
            val task = MutationOrchestrationTask.create(
                operation = SharedOperationContext.create(fixture.assumptions, dispatcher),
                occurrence = OEROccurrence(result, emptyList(), result),
                selections = selections,
                prepareField = { _, occurrence, selection ->
                    occurrence.target.getCell(selection.key).value.claim()
                    assertIs<ObjectEngineResult.MutationKey>(selection.key)
                },
                namespacePrepared = { occurrence ->
                    occurrence.target.keys.forEach { key ->
                        val cell = occurrence.target.getCell(key)
                        assertThrows<IllegalStateException> { cell.checkActivated() }
                        assertFalse(cell.fieldCheckerResult.isCompleted)
                    }
                    occurrence.parent?.let { parent ->
                        val key = assertIs<ObjectEngineResult.MutationKey>(occurrence.path.last())
                        val cell = parent.target.getCell(key)
                        cell.checkActivated()
                        assertNull(cell.fieldCheckerResult.get())
                    }
                },
            )
            task.checkDispatch()
            task.run()

            assertEquals(listOf("first", "nested", "last"), dispatched)
            assertTrue(result.isCompleted)
        }

    @Test
    fun `nested orchestration cancels each level while preserving completed mutations`(): Unit =
        runTest {
            val fixture = TestWorld.fromSDL(
                """
                directive @namespaceType on OBJECT
                type Query { idle: Int }
                type Mutation { update: Int group: Group }
                type Group @namespaceType { update: Int nested: Nested }
                type Nested @namespaceType { update: Int }
                """.trimIndent(),
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    listOf("Mutation", "Group", "Nested").associate { type ->
                        schema.requireObjectField(type, "update") to fieldResolverOf(schema.emptyFragmentOf(type)) { _, _ -> 0 }
                    }
                },
            )
            val selections = assertIs<MutationSelectionForest>(
                fixture.schemas.operationSelectionsFrom(
                    "mutation { before: update group { before: update nested { blocked: update after: update } after: update } after: update }",
                ),
            )
            val occurrences = mutableListOf<OEROccurrence>()
            val effects = mutableListOf<String>()
            val dispatcher = object : SharedTaskDispatcher<Nothing, Nothing, ObjectEngineResult.MutationKey> {
                override fun dispatchOrchestration(task: Nothing): Unit = error("Unexpected ordinary orchestration")

                override fun dispatchFieldResolver(publication: Nothing): Unit = error("Unexpected ordinary field")

                override suspend fun dispatchMutationField(
                    publication: ObjectEngineResult.MutationKey,
                    cell: EngineResultCell
                ) {
                    effects += "${publication.field.containingDef.name}.${publication.responseKey}"
                    if (publication.responseKey == "blocked") awaitCancellation()
                    cell.value.complete(effects.size)
                }
            }
            val task = MutationOrchestrationTask.createRoot(
                operation = SharedOperationContext.create(fixture.assumptions, dispatcher),
                selections = selections,
                prepareField = { _, occurrence, selection ->
                    occurrence.target.getCell(selection.key).value.claim()
                    assertIs<ObjectEngineResult.MutationKey>(selection.key)
                },
                namespacePrepared = { occurrences += it },
            )
            task.checkDispatch()
            val execution = launch { task.run() }
            runCurrent()
            assertEquals(listOf("Mutation.before", "Group.before", "Nested.blocked"), effects)
            assertEquals(listOf("Mutation", "Group", "Nested"), occurrences.map { it.target.type.name })

            execution.cancel()
            runCurrent()

            assertTrue(execution.isCompleted)
            assertEquals(listOf("Mutation.before", "Group.before", "Nested.blocked"), effects)
            occurrences.forEach { occurrence ->
                occurrence.target.keys.forEach { key ->
                    val cell = occurrence.target.getCell(key)
                    assertTrue(cell.value.isCompleted)
                    assertTrue(cell.fieldCheckerResult.isCompleted)
                    if (assertIs<ObjectEngineResult.MutationKey>(key).responseKey == "before") {
                        assertEquals(if (occurrence.target.type.name == "Mutation") 1 else 2, cell.value.get())
                    } else {
                        assertThrows<CancellationException> { cell.value.get() }
                    }
                    if (key.responseKey == "after") {
                        assertThrows<CancellationException> { cell.fieldCheckerResult.get() }
                    } else {
                        assertNull(cell.fieldCheckerResult.get())
                    }
                }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `nested production cancellation terminates namespaces during resolver and payload waits`(payload: Boolean): Unit =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val effects = mutableListOf<String>()
            val occurrences = mutableListOf<OEROccurrence>()
            var blockedFieldFinished = false
            val world = TestWorld.fromSDL(
                """
                directive @namespaceType on OBJECT
                type Query { idle: Int }
                type Mutation { update: Int group: Group }
                type Group @namespaceType { update: Int nested: Nested }
                type Nested @namespaceType { update: Int blocked: Payload }
                type Payload { value: Int }
                """.trimIndent(),
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    listOf("Mutation", "Group", "Nested").associate { type ->
                        schema.requireObjectField(type, "update") to fieldResolverOf(schema.emptyFragmentOf(type)) { _, _, context ->
                            val key = assertIs<ObjectEngineResult.MutationKey>(assertIs<FieldResolverTask>(context).publication.sourceOccurrence.selection.key)
                            effects += "$type.${key.responseKey}"
                            7
                        }
                    } + mapOf(
                        schema.requireObjectField("Nested", "blocked") to fieldResolverOf(schema.emptyFragmentOf("Nested")) { _, _ ->
                            effects += "Nested.blocked"
                            if (!payload) {
                                entered.complete(Unit)
                                awaitCancellation()
                            }
                            engineObjectDataOf(schema.requireType("Payload") as ViaductSchema.Object)
                        },
                        schema.requireObjectField("Payload", "value") to fieldResolverOf(schema.emptyFragmentOf("Payload")) { _, _ ->
                            effects += "Payload.value"
                            entered.complete(Unit)
                            awaitCancellation()
                        },
                    )
                },
            )
            val observer = object : ResolutionObserver {
                override val onTaskPrepared: (ResolutionTask) -> Unit = { task ->
                    if (task is MutationOrchestrationTask<*>) occurrences += task.occurrence
                }

                override val onTaskExecution: (ResolutionTask, ResolutionTaskState, Throwable?) -> Unit = { task, state, _ ->
                    if (task is FieldResolverTask && task.publication.sourceOccurrence.selection.key.field.name == "blocked" && state == ResolutionTaskState.FINISHED) {
                        blockedFieldFinished = true
                    }
                }
            }
            val request = Job()
            try {
                SharedOperationContext.create(world.assumptions, resolutionObserver = observer).startResolve(
                    world.schemas.operationSelectionsFrom(
                        "mutation { before: update group { before: update nested { blocked { value } after: update } after: update } after: update }",
                    ),
                    CoroutineScope(StandardTestDispatcher(testScheduler) + request),
                )
                runCurrent()
                assertTrue(entered.isCompleted)
                assertEquals(listOf("Mutation", "Group", "Nested"), occurrences.map { it.target.type.name })
                val expectedEffects = listOf("Mutation.before", "Group.before", "Nested.blocked") + if (payload) listOf("Payload.value") else emptyList()
                assertEquals(expectedEffects, effects)
                val nested = occurrences.last().target
                val blocked = nested.getCell(nested.keys.single { it.field.name == "blocked" })
                assertEquals(payload, blocked.value.isCompleted)
                assertEquals(payload, blockedFieldFinished)
                occurrences.forEach { occurrence ->
                    val after = occurrence.target.keys.single { assertIs<ObjectEngineResult.MutationKey>(it).responseKey == "after" }
                    assertFalse(occurrence.target.getCell(after).value.isCompleted)
                }

                request.cancel()
                runCurrent()
                request.join()

                assertEquals(expectedEffects, effects)
                occurrences.forEach { occurrence ->
                    assertTrue(occurrence.target.isCompleted)
                    occurrence.target.keys.forEach { key ->
                        val cell = occurrence.target.getCell(key)
                        val responseKey = assertIs<ObjectEngineResult.MutationKey>(key).responseKey
                        when {
                            responseKey == "before" -> assertEquals(7, cell.value.get())
                            payload && key.field.name == "blocked" -> {
                                val result = assertIs<ObjectEngineResult>(cell.value.get())
                                assertTrue(result.isCompleted)
                                assertThrows<CancellationException> { result.getCell(result.keys.single()).value.get() }
                            }
                            else -> assertThrows<CancellationException> { cell.value.get() }
                        }
                        if (responseKey == "after") {
                            assertThrows<CancellationException> { cell.fieldCheckerResult.get() }
                        } else {
                            assertNull(cell.fieldCheckerResult.get())
                        }
                    }
                }
            } finally {
                request.cancel()
                runCurrent()
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cancellation before orchestration entry terminates every prepared cell`(precomputed: Boolean): Unit =
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
    fun `suspended mutation prevents its successor from starting and cancels locally`(precomputed: Boolean): Unit =
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
    fun `the next mutation waits for child work after the first value is published`(): Unit =
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
