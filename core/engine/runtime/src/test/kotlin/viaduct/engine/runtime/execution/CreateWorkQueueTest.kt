package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.GraphqlErrorException
import graphql.execution.ResultPath
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class CreateWorkQueueTest {
    @Test
    fun `tasks start before collection`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val eager = task(group("A")) {
                started.complete(Unit)
                release.await()
                result("x")
            }
            val queue = CreateWorkQueue(work(eager), scope = this)

            started.await()
            release.complete(Unit)

            val events = queue.events.toList().flatten()
            fields(events).shouldContainExactly("x")
            assertEquals(WorkEvent.Terminated, events.last())
        }

    @Test
    fun `completion bursts larger than the buffer deliver every result`() =
        runTest {
            val a = group("A")
            val expected = List(2_048) { "field$it" }
            val tasks = expected.map { field -> task(a) { result(field) } }
            val queue = CreateWorkQueue(work(*tasks.toTypedArray()), scope = this)

            val events = withTimeout(1_000) { queue.events.toList().flatten() }

            fields(events).shouldContainExactly(expected)
            successes(events).shouldContainExactly(a)
            assertEquals(WorkEvent.Terminated, events.last())
        }

    @Test
    fun `late collection receives every batch after execution finishes`() =
        runTest {
            val parent = group("parent")
            val child = group("child", parent)
            var runs = 0
            val nested = task(child) {
                runs++
                result("child")
            }
            val initial = task(parent) {
                runs++
                result("parent", work(nested))
            }
            val queue = CreateWorkQueue(work(initial), scope = this)

            runCurrent()
            assertEquals(2, runs)

            val batches = queue.events.toList()
            assertEquals(2, batches.size)
            val events = batches.flatten()
            fields(events).shouldContainExactly("parent", "child")
            successes(events).shouldContainExactly(parent, child)
            assertEquals(WorkEvent.Terminated, events.last())
        }

    @Test
    fun `slow collection does not delay nested tasks`() =
        runTest {
            val parent = group("parent")
            val child = group("child", parent)
            val grandchild = group("grandchild", child)
            val readerWaiting = CompletableDeferred<Unit>()
            val releaseReader = CompletableDeferred<Unit>()
            val grandchildStarted = CompletableDeferred<Unit>()
            val deepest = task(grandchild) {
                grandchildStarted.complete(Unit)
                result("grandchild")
            }
            val nested = task(child) {
                readerWaiting.await()
                result("child", work(deepest))
            }
            val initial = task(parent) { result("parent", work(nested)) }
            val queue = CreateWorkQueue(work(initial), scope = this)
            val events = mutableListOf<WorkEvent>()
            val collection = async {
                queue.events.collect { batch ->
                    events.addAll(batch)
                    if (batch.any { it is WorkEvent.GroupSuccess && it.group === parent }) {
                        readerWaiting.complete(Unit)
                        releaseReader.await()
                    }
                }
            }

            readerWaiting.await()
            grandchildStarted.await()
            assertFalse(collection.isCompleted)
            releaseReader.complete(Unit)
            collection.await()

            fields(events).shouldContainExactly("parent", "child", "grandchild")
            successes(events).shouldContainExactly(parent, child, grandchild)
            assertEquals(WorkEvent.Terminated, events.last())
        }

    @Test
    fun `cancelling before collection stops outstanding tasks`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val hanging = task(group("A")) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val queue = CreateWorkQueue(work(hanging), scope = this)
            started.await()

            queue.cancel()
            cancelled.await()

            assertThrows<CancellationException> { queue.events.collect() }
        }

    @Test
    fun `cancelling before execution starts prevents tasks from running`() =
        runTest {
            var runs = 0
            val eager = task(group("A")) {
                runs++
                result("x")
            }
            val queue = CreateWorkQueue(work(eager), scope = this)

            queue.cancel()
            runCurrent()

            assertEquals(0, runs)
            assertThrows<CancellationException> { queue.events.collect() }
        }

    @Test
    fun `cancelling after execution finishes discards buffered events`() =
        runTest {
            val queue = CreateWorkQueue(work(task(group("A")) { result("x") }), scope = this)
            runCurrent()

            queue.cancel()

            val events = queue.events.toList().flatten()
            assertTrue(events.isEmpty())
        }

    @Test
    fun `request cancellation stops execution before collection`() =
        runTest {
            val requestJob = Job(coroutineContext[Job])
            val requestScope = CoroutineScope(coroutineContext + requestJob)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val hanging = task(group("A")) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val queue = CreateWorkQueue(work(hanging), scope = requestScope)
            started.await()

            requestJob.cancelAndJoin()

            assertTrue(cancelled.isCompleted)
            assertThrows<CancellationException> { queue.events.collect() }
        }

    @Test
    fun `request cancellation releases tasks waiting to report completions`() =
        runTest {
            val requestJob = Job(coroutineContext[Job])
            val requestScope = CoroutineScope(coroutineContext + requestJob)
            val a = group("A")
            var completedTasks = 0
            val successful = List(2_048) { index ->
                task(a) {
                    completedTasks++
                    result("field$index")
                }
            }
            val cancelling = task(a) {
                requestJob.cancel()
                awaitCancellation()
            }
            val queue = CreateWorkQueue(work(*(successful + cancelling).toTypedArray()), scope = requestScope)

            runCurrent()

            assertEquals(successful.size, completedTasks)
            assertTrue(requestJob.isCompleted)
            assertThrows<CancellationException> { queue.events.collect() }
        }

    @Test
    fun `producer failures reach a late collector without cancelling its scope`() =
        runTest {
            val failure = IllegalStateException("unexpected")
            val queue = CreateWorkQueue(work(task(group("A")) { throw failure }), scope = this)

            runCurrent()
            assertTrue(coroutineContext[Job]!!.isActive)

            val thrown = assertThrows<IllegalStateException> { queue.events.collect() }
            assertEquals(failure.message, thrown.message)
        }

    @Test
    fun `empty work terminates without pending groups`() =
        runTest {
            val queue = CreateWorkQueue(Work.empty, scope = this)
            assertTrue(queue.initialGroups.isEmpty())
            queue.events.toList().flatten().shouldContainExactly(WorkEvent.Terminated)
        }

    @Test
    fun `shared results are delivered once while unrelated tasks continue`() =
        runTest {
            val a = group("A")
            val b = group("B")
            val slow = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            var sharedRuns = 0
            val shared = task(a, b) {
                sharedRuns++
                result("x")
            }
            val unique = task(a) {
                started.complete(Unit)
                slow.await()
                result("y")
            }
            val queue = CreateWorkQueue(work(shared, unique, shared), scope = this)
            queue.initialGroups.shouldContainExactly(a, b)
            val events = mutableListOf<WorkEvent>()
            val bCompleted = CompletableDeferred<Unit>()
            val collection = async {
                queue.events.collect { batch ->
                    events.addAll(batch)
                    if (batch.any { it is WorkEvent.GroupSuccess && it.group === b }) bCompleted.complete(Unit)
                }
            }
            started.await()
            bCompleted.await()
            assertFalse(collection.isCompleted)
            assertEquals(1, sharedRuns)
            assertEquals(listOf("x"), fields(events))
            assertEquals(listOf(b), successes(events))
            slow.complete(Unit)
            collection.await()
            fields(events).shouldContainExactly("x", "y")
            successes(events).shouldContainExactly(b, a)
            assertEquals(WorkEvent.Terminated, events.last())
        }

    @Test
    fun `nested work extends its current group before it succeeds`() =
        runTest {
            val parent = group("parent")
            val child = group("child", parent)
            val gate = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            var childRuns = 0
            val extension = task(parent) {
                started.complete(Unit)
                gate.await()
                result("extension")
            }
            val nested = task(child) {
                childRuns++
                result("child")
            }
            val initial = task(parent) { result("parent", work(extension, nested)) }
            val queue = CreateWorkQueue(work(initial), scope = this)
            queue.initialGroups.shouldContainExactly(parent)
            val events = mutableListOf<WorkEvent>()
            val collection = async { queue.events.collect { events.addAll(it) } }
            started.await()
            assertTrue(events.isEmpty())
            assertEquals(0, childRuns)
            gate.complete(Unit)
            collection.await()
            fields(events).shouldContainExactly("parent", "extension", "child")
            successes(events).shouldContainExactly(parent, child)
            val completion = events.filterIsInstance<WorkEvent.GroupSuccess>().first()
            completion.newGroups.shouldContainExactly(child)
        }

    @Test
    fun `shared values use a pending group while a longer path group waits`() =
        runTest {
            val a = group("A")
            val p = group("P")
            val b = DeferDeliveryGroup(ResultPath.rootPath().segment("hero"), "B", p)
            val gate = CompletableDeferred<Unit>()
            val aCompleted = CompletableDeferred<Unit>()
            var sharedRuns = 0
            var bRuns = 0
            val shared = ExecutionGroupTask(setOf(a, b), b.path) {
                sharedRuns++
                result("x")
            }
            val parent = task(p) {
                gate.await()
                result("p")
            }
            val unique = ExecutionGroupTask(setOf(b), b.path) {
                bRuns++
                result("y")
            }
            val queue = CreateWorkQueue(work(shared, parent, unique), scope = this)
            queue.initialGroups.shouldContainExactly(a, p)
            val events = mutableListOf<WorkEvent>()
            val collection = async {
                queue.events.collect { batch ->
                    events.addAll(batch)
                    if (batch.any { it is WorkEvent.GroupSuccess && it.group === a }) aCompleted.complete(Unit)
                }
            }
            aCompleted.await()
            assertEquals(0, bRuns)
            val sharedValues = events.filterIsInstance<WorkEvent.GroupValues>().single()
            assertEquals(a, sharedValues.group)
            sharedValues.values.shouldContainExactly(GroupValue(b.path, mapOf("x" to "x"), emptyList()))
            gate.complete(Unit)
            collection.await()
            assertEquals(1, sharedRuns)
            fields(events).shouldContainExactly("x", "p", "y")
            successes(events).shouldContainExactly(a, p, b)
        }

    @Test
    fun `skipped groups make their children pending`() =
        runTest {
            val a = group("A")
            val p = group("P")
            val empty = group("empty", p)
            val b = group("B", empty)
            val child = group("child", b)
            val gate = CompletableDeferred<Unit>()
            val shared = task(a, b) { result("x") }
            val parent = task(p) {
                gate.await()
                result("p")
            }
            val nested = task(child) { result("child") }
            val queue = CreateWorkQueue(work(shared, parent, nested), scope = this)
            val events = mutableListOf<WorkEvent>()
            queue.events.collect { batch ->
                events.addAll(batch)
                if (batch.any { it is WorkEvent.GroupSuccess && it.group === a }) gate.complete(Unit)
            }
            successes(events).shouldContainExactly(a, p, child)
            events.filterIsInstance<WorkEvent.GroupSuccess>().flatMap { it.newGroups }.shouldContainExactly(child)

            val initialEmpty = group("initial empty")
            val leaf = group("leaf", initialEmpty)
            val promoted = CreateWorkQueue(work(task(leaf) { result("leaf") }), scope = this)
            promoted.initialGroups.shouldContainExactly(leaf)
            promoted.events.toList()
        }

    @Test
    fun `shared values use the longest announced path even if that group is still pending`() =
        runTest {
            val a = group("A")
            val path = ResultPath.rootPath().segment("hero").segment(1)
            val b = DeferDeliveryGroup(path, "B", null)
            val gate = CompletableDeferred<Unit>()
            val shared = ExecutionGroupTask(setOf(a, b), path) { result("name") }
            val slow = ExecutionGroupTask(setOf(b), path) {
                gate.await()
                result("id")
            }
            val events = mutableListOf<WorkEvent>()
            CreateWorkQueue(work(shared, slow), scope = this).events.collect { batch ->
                events.addAll(batch)
                if (batch.any { it is WorkEvent.GroupSuccess && it.group === a }) {
                    assertEquals(b, batch.filterIsInstance<WorkEvent.GroupValues>().single().group)
                    gate.complete(Unit)
                }
            }
            fields(events).shouldContainExactly("name", "id")
        }

    @Test
    fun `failed groups cancel orphan work and suppress children without cancelling siblings`() =
        runTest {
            val a = group("A")
            val b = group("B")
            val child = group("child", a)
            val orphanStarted = CompletableDeferred<Unit>()
            val orphanCancelled = CompletableDeferred<Unit>()
            var childRuns = 0
            val errors = listOf(error("first"), error("second"))
            val failure = task(a) {
                orphanStarted.await()
                ExecutionGroupResult(null, errors, Work.empty)
            }
            val orphan = task(a) {
                try {
                    orphanStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    orphanCancelled.complete(Unit)
                }
            }
            val sibling = task(b) {
                orphanCancelled.await()
                result("sibling")
            }
            val nested = task(child) {
                childRuns++
                result("child")
            }
            val events = CreateWorkQueue(work(failure, orphan, sibling, nested), scope = this).events.toList().flatten()
            assertEquals(0, childRuns)
            events.filterIsInstance<WorkEvent.GroupFailure>().shouldContainExactly(WorkEvent.GroupFailure(a, errors))
            fields(events).shouldContainExactly("sibling")
            successes(events).shouldContainExactly(b)
            assertEquals(WorkEvent.Terminated, events.last())
        }

    @Test
    fun `late successful results from cancelled tasks do not add work to surviving groups`() =
        runTest {
            val a = group("A")
            val b = group("B")
            val started = CompletableDeferred<Unit>()
            val returnedAfterCancellation = CompletableDeferred<Unit>()
            var nestedRuns = 0
            val nested = task(b) {
                nestedRuns++
                result("nested")
            }
            val errors = listOf(error("failed"))
            val failure = task(a) {
                started.await()
                ExecutionGroupResult(null, errors, Work.empty)
            }
            val late = task(a) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    returnedAfterCancellation.complete(Unit)
                    result("late", work(nested))
                }
            }
            val survivor = task(b) {
                returnedAfterCancellation.await()
                result("survivor")
            }

            val events = CreateWorkQueue(work(failure, late, survivor), scope = this).events.toList().flatten()

            assertTrue(returnedAfterCancellation.isCompleted)
            assertEquals(0, nestedRuns)
            events.shouldContainExactly(
                WorkEvent.GroupFailure(a, errors),
                WorkEvent.GroupValues(b, listOf(GroupValue(b.path, mapOf("survivor" to "survivor"), emptyList()))),
                WorkEvent.GroupSuccess(b, emptyList()),
                WorkEvent.Terminated,
            )
        }

    @Test
    fun `failure of one participant does not cancel a shared task needed by another`() =
        runTest {
            val a = group("A")
            val b = group("B")
            val release = CompletableDeferred<Unit>()
            val shared = task(a, b) {
                release.await()
                result("shared")
            }
            val failure = task(a) { ExecutionGroupResult(null, listOf(error("failure")), Work.empty) }
            val events = mutableListOf<WorkEvent>()
            CreateWorkQueue(work(shared, failure), scope = this).events.collect { batch ->
                events.addAll(batch)
                if (batch.any { it is WorkEvent.GroupFailure }) release.complete(Unit)
            }
            fields(events).shouldContainExactly("shared")
            successes(events).shouldContainExactly(b)
        }

    @Test
    fun `shared failures are retained for a waiting group`() =
        runTest {
            val a = group("A")
            val p = group("P")
            val b = group("B", p)
            val release = CompletableDeferred<Unit>()
            val failure = task(a, b) { ExecutionGroupResult(null, listOf(error("failure")), Work.empty) }
            val parent = task(p) {
                release.await()
                result("parent")
            }
            val events = mutableListOf<WorkEvent>()
            CreateWorkQueue(work(failure, parent), scope = this).events.collect { batch ->
                events.addAll(batch)
                if (batch.any { it is WorkEvent.GroupFailure && it.group === a }) release.complete(Unit)
            }
            events.filterIsInstance<WorkEvent.GroupFailure>().map { it.group }.shouldContainExactly(a, b)
            successes(events).shouldContainExactly(p)
        }

    @Test
    fun `a newly pending group can deliver already completed shared results`() =
        runTest {
            val a = group("A")
            val p = group("P")
            val b = group("B", p)
            val releaseA = CompletableDeferred<Unit>()
            val shared = task(a, b) { result("x") }
            val parent = task(p) { result("p") }
            val slow = task(a) {
                releaseA.await()
                result("a")
            }
            val events = mutableListOf<WorkEvent>()
            CreateWorkQueue(work(shared, parent, slow), scope = this).events.collect { batch ->
                events.addAll(batch)
                if (batch.any { it is WorkEvent.GroupSuccess && it.group === b }) releaseA.complete(Unit)
            }
            successes(events).shouldContainExactly(p, b, a)
            fields(events).shouldContainExactly("p", "x", "a")
        }

    @Test
    fun `shared values do not use a failed owner from the same completion batch`() =
        runTest {
            val a = group("A")
            val path = ResultPath.rootPath().segment("hero")
            val b = DeferDeliveryGroup(path, "B", null)
            val shared = ExecutionGroupTask(setOf(a, b), path) { result("x") }
            val failure = ExecutionGroupTask(setOf(b), path) {
                ExecutionGroupResult(null, listOf(error("failed")), Work.empty)
            }
            val events = CreateWorkQueue(work(shared, failure), scope = this).events.toList().flatten()
            assertEquals(a, events.filterIsInstance<WorkEvent.GroupValues>().single().group)
            successes(events).shouldContainExactly(a)
            assertEquals(b, events.filterIsInstance<WorkEvent.GroupFailure>().single().group)
        }

    @Test
    fun `GraphQL exceptions fail their groups without failing the stream`() =
        runTest {
            val a = group("A")
            val failure = GraphqlErrorException.newErrorException().message("failed").build()
            val events = CreateWorkQueue(work(task(a) { throw failure }), scope = this).events.toList().flatten()
            events.shouldContainExactly(WorkEvent.GroupFailure(a, listOf(failure)), WorkEvent.Terminated)
        }

    @Test
    fun `nullable errors travel with values and do not fail the group`() =
        runTest {
            val a = group("A")
            val errors = listOf(error("nullable field failed"))
            val task = task(a) { ExecutionGroupResult(mapOf("x" to null), errors, Work.empty) }
            val events = CreateWorkQueue(work(task), scope = this).events.toList().flatten()
            assertEquals(errors, events.filterIsInstance<WorkEvent.GroupValues>().single().values.single().errors)
            assertTrue(events.none { it is WorkEvent.GroupFailure })
            successes(events).shouldContainExactly(a)
        }

    @Test
    fun `consumer stopping cancels outstanding tasks`() =
        runTest {
            val a = group("A")
            val b = group("B")
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val quick = task(a) {
                started.await()
                result("quick")
            }
            val hanging = task(b) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            CreateWorkQueue(work(quick, hanging), scope = this).events.take(1).collect()
            cancelled.await()
        }

    @Test
    fun `unexpected exceptions terminate the stream and cancel other tasks`() =
        runTest {
            val a = group("A")
            val b = group("B")
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val failure = task(a) {
                started.await()
                throw IllegalStateException("unexpected")
            }
            val hanging = task(b) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            assertThrows<IllegalStateException> {
                CreateWorkQueue(work(failure, hanging), scope = this).events.collect()
            }
            assertTrue(cancelled.isCompleted)
        }

    @Test
    fun `a second collector cannot rerun request work`() =
        runTest {
            val queue = CreateWorkQueue(work(task(group("A")) { result("x") }), scope = this)
            queue.events.collect()
            assertThrows<IllegalStateException> { queue.events.collect() }
        }

    @Test
    fun `task cancellation terminates the stream instead of waiting for a missing result`() =
        runTest {
            val queue = CreateWorkQueue(work(task(group("A")) { throw CancellationException("cancelled") }), scope = this)
            assertThrows<CancellationException> { queue.events.collect() }
        }

    @Test
    fun `cancelling the task coroutine reports cancellation alongside other completions`() =
        runTest {
            val a = group("A")
            val successful = List(2_048) { index -> task(a) { result("field$index") } }
            val selfCancelling = task(a) {
                currentCoroutineContext().cancel(CancellationException("task cancelled"))
                awaitCancellation()
            }
            val queue = CreateWorkQueue(work(*(successful + selfCancelling).toTypedArray()), scope = this)

            val thrown = assertThrows<CancellationException> {
                withTimeout(1_000) { queue.events.collect() }
            }

            assertEquals("task cancelled", thrown.message)
            assertTrue(coroutineContext[Job]!!.isActive)
        }

    private fun group(
        label: String,
        parent: DeferDeliveryGroup? = null
    ) = DeferDeliveryGroup(ResultPath.rootPath(), label, parent)

    private fun task(
        vararg groups: DeferDeliveryGroup,
        execute: suspend () -> ExecutionGroupResult
    ) = ExecutionGroupTask(groups.toSet(), ResultPath.rootPath(), execute)

    private fun work(vararg tasks: ExecutionGroupTask) = Work(tasks.flatMap { it.deferredFragments }.toSet(), tasks.toList())

    private fun result(
        field: String,
        work: Work = Work.empty
    ) = ExecutionGroupResult(mapOf(field to field), emptyList(), work)

    private fun error(message: String): GraphQLError = GraphQLError.newError().message(message).build()

    private fun fields(events: List<WorkEvent>) =
        events.filterIsInstance<WorkEvent.GroupValues>().flatMap { event ->
            event.values.flatMap { it.data.keys }
        }

    private fun successes(events: List<WorkEvent>) = events.filterIsInstance<WorkEvent.GroupSuccess>().map { it.group }
}
