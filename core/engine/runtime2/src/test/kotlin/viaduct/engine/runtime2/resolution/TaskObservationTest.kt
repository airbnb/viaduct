@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import viaduct.engine.runtime2.contract.get
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolutionTask
import viaduct.engine.runtime2.resolution.framework.ResolutionTaskState
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.SharedOrchestrationTask
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class TaskObservationTest : ResolutionDispatcherResource {
    @Test
    fun `either task callback can be subscribed independently`() {
        for (type in listOf("Query", "Mutation")) {
            val world = TestWorld.fromSDL(
                "type Query { value: Int } type Mutation { value: Int }",
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(schema.requireObjectField(type, "value") to fieldResolverOf(schema.emptyFragmentOf(type)) { _, _ -> 7 })
                }
            )
            for (prepareOnly in listOf(false, true)) {
                val prepared = ConcurrentLinkedQueue<ResolutionTask>()
                val execution = ConcurrentLinkedQueue<Execution>()
                val observer = object : ResolutionObserver {
                    override val onTaskPrepared: ((ResolutionTask) -> Unit)? = if (prepareOnly) {
                        { task ->
                            prepared.add(task)
                            Unit
                        }
                    } else {
                        null
                    }
                    override val onTaskExecution: ((ResolutionTask, ResolutionTaskState, Throwable?) -> Unit)? = if (prepareOnly) {
                        null
                    } else {
                        { task, state, cause ->
                            execution.add(Execution(task, state, cause))
                            Unit
                        }
                    }
                }
                val operation = SharedOperationContext.create(world.assumptions, resolutionObserver = observer)
                val root = operation.resolveWithTestDispatcher(world.schemas.operationSelectionsFrom(if (type == "Mutation") "mutation { value }" else "{ value }"))
                assertEquals(7, root.getCell(root.keys.single()).get())
                if (prepareOnly) {
                    assertEquals(2, prepared.size)
                    val field = prepared.filterIsInstance<FieldResolverTask>().single()
                    assertSame(prepared.single { it !== field }, field.creator)
                    assertTrue(execution.isEmpty())
                } else {
                    assertTrue(prepared.isEmpty())
                    val tasks = execution.map { it.task }.toSet()
                    assertEquals(2, tasks.size)
                    val field = tasks.filterIsInstance<FieldResolverTask>().single()
                    assertSame(tasks.single { it !== field }, field.creator)
                    execution.groupBy { it.task }.values.forEach {
                        assertEquals(listOf(ResolutionTaskState.STARTED, ResolutionTaskState.FINISHED), it.map { event -> event.state })
                    }
                }
            }
        }
    }

    @Test
    fun `task track preserves orchestration ownership and separate prepare and run moments`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { viewer: User! @resolver(result: {first: 2, last: 3}) }
            type User {
              first: Int!
              last: Int!
              display: Int! @resolver(of: "first last", result: "sum(first, last)")
              greeting: Int! @resolver(of: "display", result: "sumplus1(display)")
            }
            """.trimIndent()
        )
        val log = TaskLog()
        val operation = SharedOperationContext.create(world.assumptions, resolutionObserver = CorrectnessResolverObserver(log))
        val root = operation.resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ viewer { greeting } }"))
        val user = assertIs<ObjectEngineResult>(root.getCell(root.keys.single()).get())
        assertEquals(6, user.getCell(user.keys.single { it.field.name == "greeting" }).get())

        val prepared = log.prepared.toList()
        assertEquals(5, prepared.size)
        val rootTask = prepared.filterIsInstance<OrchestrationTask>().single { it.path.isEmpty() }
        assertNull(rootTask.creator)
        assertSame(root, rootTask.occurrence.target)
        val viewerTask = prepared.filterIsInstance<FieldResolverTask>().single { it.path.size == 1 }
        assertSame(rootTask, viewerTask.creator)
        val userTask = prepared.filterIsInstance<OrchestrationTask>().single { it.path.isNotEmpty() }
        assertSame(viewerTask, userTask.creator)
        assertSame(user, userTask.occurrence.target)
        assertSame(root, userTask.occurrence.root)
        assertEquals(viewerTask.path, userTask.path)
        prepared.filterIsInstance<FieldResolverTask>().filter { it.path.size == 2 }.forEach {
            assertSame(userTask, it.creator)
            assertSame(user, it.occurrence.target)
        }
        listOf(rootTask, userTask).forEach { assertSame(it.queryOER.occurrence.target, it.queryOER.occurrence.root) }
        log.assertComplete()
    }

    @Test
    fun `list reference tasks retain their field creator and independently rooted Query tasks`() {
        val world = TestWorld.fromSDL(
            "type Query { numbers: [Int!]! target: Int! source: Int! }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val empty = schema.emptyFragmentOf("Query")
                val target = schema.requireObjectField("Query", "target")
                mapOf(
                    schema.requireObjectField("Query", "numbers") to fieldResolverOf(empty) { _, _ ->
                        List(2) { RootFieldReferenceData.of(listOf(target), emptyMap()) }
                    },
                    target to fieldResolverOf(empty, schemas.fragmentFrom("fragment Input on Query { source }")) { _, query, _ -> query.get("source") },
                    schema.requireObjectField("Query", "source") to fieldResolverOf(empty) { _, _ -> 7 },
                )
            }
        )
        val log = TaskLog()
        val root = SharedOperationContext.create(world.assumptions, resolutionObserver = log)
            .resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ numbers }"))
        val values = assertIs<ListEngineResult>(root.getCell(root.keys.single()).get())
        assertEquals(listOf(7, 7), values.map { it.get() })
        val prepared = log.prepared.toList()
        val numbers = prepared.filterIsInstance<FieldResolverTask>().single { it.path.size == 1 && it.occurrence.root === root }
        val references = prepared.filterIsInstance<FieldResolverTask>().filter { it.path.size == 2 }
        assertEquals(2, references.size)
        assertEquals(2, references.toSet().size)
        references.forEach { assertSame(numbers, it.creator) }
        val independent = prepared.filterIsInstance<OrchestrationTask>().filter { it.occurrence.root !== root }
        assertEquals(2, independent.size)
        assertEquals(references.toSet(), independent.map { it.creator }.toSet())
        assertTrue(independent[0].occurrence.root !== independent[1].occurrence.root)
        independent.forEach { query ->
            val source = prepared.filterIsInstance<FieldResolverTask>().single { it.occurrence.root === query.occurrence.root }
            assertSame(query, source.creator)
        }
        log.assertComplete()
    }

    @Test
    fun `shared Query fields belong to the paired object orchestration`() {
        val world = TestWorld.fromSDL(
            "type Query { consumer: Int source: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val empty = schema.emptyFragmentOf("Query")
                mapOf(
                    schema.requireObjectField("Query", "consumer") to fieldResolverOf(empty, schemas.fragmentFrom("fragment Input on Query { source }")) { _, query, _ -> query.get("source") },
                    schema.requireObjectField("Query", "source") to fieldResolverOf(empty) { _, _ -> 11 },
                )
            }
        )
        val log = TaskLog()
        val root = SharedOperationContext.create(world.assumptions, resolutionObserver = log)
            .resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ consumer }"))
        assertEquals(11, root.getCell(root.keys.single()).get())
        val orchestration = log.prepared.filterIsInstance<OrchestrationTask>().single()
        val source = log.prepared.filterIsInstance<FieldResolverTask>().single { it.occurrence.root !== root }
        assertSame(orchestration.queryOER.occurrence.target, source.occurrence.target)
        assertSame(orchestration, source.creator)
        log.assertComplete()
    }

    @Test
    fun `mutation namespace orchestrations own their field tasks`() {
        val world = TestWorld.fromSDL(
            "directive @namespaceType on OBJECT type Query { unused: Int } type Mutation { nested: Namespace } type Namespace @namespaceType { value: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(schema.requireObjectField("Namespace", "value") to fieldResolverOf(schema.emptyFragmentOf("Namespace")) { _, _ -> 13 })
            }
        )
        val log = TaskLog()
        SharedOperationContext.create(world.assumptions, resolutionObserver = log)
            .resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("mutation { nested { value } }"))
        val orchestrations = log.prepared.filterIsInstance<MutationOrchestrationTask<*>>()
        assertEquals(2, orchestrations.size)
        val root = orchestrations.single { it.path.isEmpty() }
        val child = orchestrations.single { it.path.isNotEmpty() }
        assertNull(root.creator)
        assertSame(root, child.creator)
        assertSame(child, log.prepared.filterIsInstance<FieldResolverTask>().single().creator)
        log.assertComplete()
    }

    @Test
    fun `nested executions retain the calling field as creator`() {
        val world = TestWorld.fromSDL(
            "type Query { outer: Int inner: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Query", "outer") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, context ->
                        context.resolveSelectionSet(schemas.fragmentFrom("fragment Inner on Query { inner }").materializeSelections).get("inner")
                    },
                    schema.requireObjectField("Query", "inner") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 9 },
                )
            }
        )
        val log = TaskLog()
        val root = SharedOperationContext.create(world.assumptions, resolutionObserver = log)
            .resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ outer }"))
        assertEquals(9, root.getCell(root.keys.single()).get())
        val outer = log.prepared.filterIsInstance<FieldResolverTask>().single { it.occurrence.root === root }
        val nested = log.prepared.filterIsInstance<OrchestrationTask>().single { it.occurrence.root !== root }
        assertSame(outer, nested.creator)
        log.assertComplete()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancellation records termination both before entry and while suspended`() =
        runTest {
            for (type in listOf("Query", "Mutation")) {
                for (enter in listOf(false, true)) {
                    val world = TestWorld.fromSDL(
                        "type Query { value: Int } type Mutation { value: Int }",
                        fieldResolvers = { schemas ->
                            val schema = schemas.loweredSchema
                            mapOf(schema.requireObjectField(type, "value") to fieldResolverOf(schema.emptyFragmentOf(type)) { _, _ -> awaitCancellation() })
                        }
                    )
                    val log = TaskLog()
                    val request = Job()
                    val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + request)
                    val root = SharedOperationContext.create(world.assumptions, resolutionObserver = log)
                        .startResolve(world.schemas.operationSelectionsFrom(if (type == "Mutation") "mutation { value }" else "{ value }"), scope)
                    if (enter) runCurrent()
                    request.cancel()
                    runCurrent()
                    request.join()
                    val field = log.prepared.filterIsInstance<FieldResolverTask>().single()
                    val events = log.execution.filter { it.task === field }
                    assertEquals(if (enter) 1 else 0, events.count { it.state == ResolutionTaskState.STARTED })
                    assertTrue(events.any { it.state == ResolutionTaskState.FINISHED })
                    assertEquals(if (enter) ResolutionTaskState.STARTED else ResolutionTaskState.FINISHED, events.first().state)
                    assertIs<CancellationException>(events.last().cause)
                    assertTrue(root.getCell(root.keys.single()).value.isCompleted)
                    log.prepared.forEach { task ->
                        assertTrue(log.execution.any { it.task === task && it.state == ResolutionTaskState.FINISHED })
                    }
                }
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `field termination is observed before cancellation cleanup even if the observer throws`() =
        runTest {
            val world = TestWorld.fromDSL("extend type Query { value: Int @resolver(result: 7) }")
            for (throwFromObserver in listOf(false, true)) {
                val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
                val completedAtFinish = mutableListOf<Boolean>()
                val failures = mutableListOf<Throwable>()
                val observerFailure = IllegalStateException("observer failure")
                val observer = object : ResolutionObserver {
                    override val onTaskExecution: (ResolutionTask, ResolutionTaskState, Throwable?) -> Unit = { task, state, _ ->
                        if (task is FieldResolverTask && state == ResolutionTaskState.FINISHED) {
                            val completed = root.getCell(root.keys.single()).value.isCompleted
                            completedAtFinish += completed
                            if (throwFromObserver && !completed) throw observerFailure
                        }
                    }
                }
                val dispatcher = StandardTestDispatcher(testScheduler)
                val request = Job()
                val fieldScope = Job()
                try {
                    val operation = OperationContext.create(
                        SharedOperationContext.create(world.assumptions, resolutionObserver = observer),
                        CoroutineScope(dispatcher + request),
                    )
                    val orchestration = OrchestrationTask.create(
                        operation,
                        OEROccurrence(root, emptyList(), root),
                        world.assumptions.resolverRegistry.createRootQueryInput(),
                        world.schemas.operationSelectionsFrom("{ value }"),
                    )
                    val publication = FieldResolverTask.prepareAll(orchestration).single()
                    publication.dispatch(CoroutineScope(dispatcher + fieldScope + CoroutineExceptionHandler { _, cause -> failures.add(cause) }))
                    fieldScope.cancel()
                    runCurrent()

                    assertEquals(listOf(false), completedAtFinish)
                    assertFailsWith<CancellationException> { publication.publicationCell.value.get() }
                    assertFailsWith<CancellationException> { publication.publicationCell.fieldCheckerResult.get() }
                    if (throwFromObserver) {
                        val reported = failures.single()
                        assertSame(observerFailure, reported.cause)
                    } else {
                        assertTrue(failures.isEmpty())
                    }
                } finally {
                    fieldScope.cancel()
                    request.cancel()
                    runCurrent()
                }
            }
        }

    @Test
    fun `orchestration records exceptions and propagates fatal errors without a completion event`() {
        val world = TestWorld.fromDSL("extend type Query { value: Int @resolver(result: 7) }")
        for (failure in listOf(IllegalStateException("orchestration failure"), CancellationException("cancelled"), AssertionError("fatal failure"))) {
            val log = TaskLog()
            val operation = SharedOperationContext.create(world.assumptions, resolutionObserver = log)
            val objectOER = SharedOERContext.undemandedQuery(operation.world.schema.requireQueryTypeDef())
            val task = object : CoroutineOrchestrationTaskBase<SharedOperationContext<*>>(operation, objectOER) {
                override val hasActiveWork = false

                override fun prepareAndDispatchFieldWork() {
                    throw failure
                }
            }

            assertSame(failure, assertFailsWith<Throwable> { task.run() })
            val events = log.execution.toList()
            events.forEach { assertSame(task, it.task) }
            if (failure is Exception) {
                assertEquals(listOf(ResolutionTaskState.STARTED, ResolutionTaskState.FINISHED), events.map { it.state })
                assertSame(failure, events.last().cause)
            } else {
                assertEquals(listOf(ResolutionTaskState.STARTED), events.map { it.state })
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `fatal field task failures are reported as termination causes`() =
        runTest {
            for (type in listOf("Query", "Mutation")) {
                val failure = AssertionError("fatal tenant failure")
                val reported = CompletableDeferred<Throwable>()
                val world = TestWorld.fromSDL(
                    "type Query { value: Int } type Mutation { value: Int }",
                    fieldResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        mapOf(schema.requireObjectField(type, "value") to fieldResolverOf(schema.emptyFragmentOf(type)) { _, _ -> throw failure })
                    }
                )
                val log = TaskLog()
                val request = Job()
                val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + request + CoroutineExceptionHandler { _, cause -> reported.complete(cause) })
                SharedOperationContext.create(world.assumptions, resolutionObserver = log)
                    .startResolve(world.schemas.operationSelectionsFrom(if (type == "Mutation") "mutation { value }" else "{ value }"), scope)
                runCurrent()
                request.join()
                val reportedFailure = assertIs<AssertionError>(reported.await())
                assertSame(failure, if (reportedFailure === failure) reportedFailure else reportedFailure.cause)
                val field = log.prepared.filterIsInstance<FieldResolverTask>().single()
                val events = log.execution.filter { it.task === field }
                assertEquals(ResolutionTaskState.STARTED, events.first().state)
                assertTrue(events.any { it.state == ResolutionTaskState.FINISHED && it.cause === failure })
                val orchestration = log.prepared.single { it !== field }
                val orchestrationStates = log.execution.filter { it.task === orchestration }.map { it.state }
                assertEquals(
                    if (type == "Mutation") listOf(ResolutionTaskState.STARTED) else listOf(ResolutionTaskState.STARTED, ResolutionTaskState.FINISHED),
                    orchestrationStates,
                )
            }
        }

    @Test
    fun `field errors complete the task normally and callbacks default to null`() {
        val world = TestWorld.fromSDL(
            "type Query { value: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(schema.requireObjectField("Query", "value") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> error("tenant failure") })
            }
        )
        val log = TaskLog()
        val root = SharedOperationContext.create(world.assumptions, resolutionObserver = log)
            .resolveWithTestDispatcher(world.schemas.operationSelectionsFrom("{ value }"))
        assertIs<ErrorEngineResult>(root.getCell(root.keys.single()).get())
        log.assertComplete()
        assertNull(ResolutionObserver.NOP.onTaskPrepared)
        assertNull(ResolutionObserver.NOP.onTaskExecution)
    }

    private class TaskLog : ResolutionObserver {
        val prepared = ConcurrentLinkedQueue<ResolutionTask>()
        val execution = ConcurrentLinkedQueue<Execution>()
        val events = ConcurrentLinkedQueue<Any>()
        override val onTaskPrepared: (ResolutionTask) -> Unit = {
            prepared.add(it)
            events.add(it)
        }
        override val onTaskExecution: (ResolutionTask, ResolutionTaskState, Throwable?) -> Unit = { task, state, cause ->
            val event = Execution(task, state, cause)
            execution.add(event)
            events.add(event)
        }

        fun assertComplete() {
            assertEquals(prepared.size, prepared.toSet().size)
            assertEquals(prepared.toSet(), execution.map { it.task }.toSet())
            prepared.forEach { task ->
                val states = execution.filter { it.task === task }
                assertEquals(listOf(ResolutionTaskState.STARTED, ResolutionTaskState.FINISHED), states.map { it.state })
                states.forEach { assertNull(it.cause) }
                assertTrue(events.indexOf(task) < events.indexOf(states.first()))
            }
        }
    }
}

private data class Execution(val task: ResolutionTask, val state: ResolutionTaskState, val cause: Throwable?)

private val ResolutionTask.occurrence: OEROccurrence
    get() = when (this) {
        is SharedOrchestrationTask<*> -> objectOER.occurrence
        is MutationOrchestrationTask<*> -> namespace.occurrence
        is FieldResolverTask -> publication.oerOccurrence
        else -> error("Unexpected observed task: $this")
    }

private val ResolutionTask.path: List<PathComponent>
    get() = if (this is FieldResolverTask) publication.sourceOccurrence.publicationPath else occurrence.path
