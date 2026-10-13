package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.ResultPath
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkGraphTest {
    @Test
    fun `empty work terminates`() {
        val graph = WorkGraph(Work.empty)

        graph.markInitialGroupsPending().shouldBeEmpty()
        graph.claimTasks().shouldBeEmpty()
        graph.processPendingGroups().shouldContainExactly(WorkEvent.Terminated)
        assertFalse(graph.hasPendingGroups())
    }

    @Test
    fun `empty ancestors make their nonempty descendants pending`() {
        val parent = group("parent")
        val middle = group("middle", parent)
        val child = group("child", middle)
        val task = task(child)
        val graph = WorkGraph(work(task))

        graph.markInitialGroupsPending().shouldContainExactly(child)
        graph.markInitialGroupsPending().shouldBeEmpty()
        graph.claimTasks().shouldContainExactly(task)
    }

    @Test
    fun `child tasks can be claimed after their parent succeeds`() {
        val parent = group("parent")
        val child = group("child", parent)
        val parentTask = task(parent)
        val childTask = task(child)
        val graph = WorkGraph(work(parentTask, childTask))
        val parentResult = result("parent")

        assertFalse(graph.canDiscard(childTask))
        graph.markInitialGroupsPending().shouldContainExactly(parent)
        graph.claimTasks().shouldContainExactly(parentTask)
        graph.completeTasks(listOf(parentTask to parentResult))

        graph.processPendingGroups().shouldContainExactly(values(parent, parentTask, parentResult), WorkEvent.GroupSuccess(parent, listOf(child)))
        graph.claimTasks().shouldContainExactly(childTask)
        assertTrue(graph.hasPendingGroups())
    }

    @Test
    fun `groups with already delivered results are skipped and make their children pending`() {
        val parent = group("parent")
        val child = group("child", parent)
        val grandchild = group("grandchild", child)
        val shared = task(parent, child)
        val nested = task(grandchild)
        val graph = WorkGraph(work(shared, nested))
        val sharedResult = result("shared")
        val nestedResult = result("nested")
        graph.markInitialGroupsPending().shouldContainExactly(parent)
        graph.claimTasks().shouldContainExactly(shared)

        graph.completeTasks(listOf(shared to sharedResult))

        graph.processPendingGroups().shouldContainExactly(values(parent, shared, sharedResult), WorkEvent.GroupSuccess(parent, listOf(grandchild)))
        assertTrue(graph.canDiscard(shared))
        assertFalse(graph.canDiscard(nested))
        graph.markInitialGroupsPending().shouldBeEmpty()
        graph.claimTasks().shouldContainExactly(nested)
        graph.completeTasks(listOf(nested to nestedResult))
        graph.processPendingGroups().shouldContainExactly(
            values(grandchild, nested, nestedResult),
            WorkEvent.GroupSuccess(grandchild, emptyList()),
            WorkEvent.Terminated,
        )
    }

    @Test
    fun `a shared task is claimed only once`() {
        val a = group("A")
        val b = group("B")
        val shared = task(a, b)
        val graph = WorkGraph(work(shared, shared))
        graph.markInitialGroupsPending().shouldContainExactly(a, b)

        graph.claimTasks().shouldContainExactly(shared)
        graph.claimTasks().shouldBeEmpty()
    }

    @Test
    fun `shared results are delivered once under the longest announced path`() {
        val a = group("A")
        val b = group("B", path = ResultPath.rootPath().segment("items").segment(0))
        val shared = task(a, b, path = b.path)
        val remaining = task(a)
        val graph = WorkGraph(work(shared, remaining))
        val sharedResult = result("shared")
        val remainingResult = result("remaining")
        graph.markInitialGroupsPending()
        graph.claimTasks().shouldContainExactlyInAnyOrder(shared, remaining)

        graph.completeTasks(listOf(shared to sharedResult))
        graph.processPendingGroups().shouldContainExactly(values(b, shared, sharedResult), WorkEvent.GroupSuccess(b, emptyList()))
        assertTrue(graph.hasPendingGroups())
        assertFalse(graph.canDiscard(shared))

        graph.completeTasks(listOf(remaining to remainingResult))
        graph.processPendingGroups().shouldContainExactly(values(a, remaining, remainingResult), WorkEvent.GroupSuccess(a, emptyList()), WorkEvent.Terminated)
        assertFalse(graph.hasPendingGroups())
        assertTrue(graph.canDiscard(shared))
    }

    @Test
    fun `duplicate completions within a batch are rejected`() {
        val group = group("A")
        val task = task(group)
        val graph = WorkGraph(work(task))
        val completed = result("field")
        val duplicate = result("replacement")
        graph.markInitialGroupsPending()
        graph.claimTasks()

        assertThrows<IllegalArgumentException> { graph.completeTasks(listOf(task to completed, task to duplicate)) }
    }

    @Test
    fun `duplicate completions after delivery are rejected without replacing the shared result`() {
        val a = group("A")
        val b = group("B", path = ResultPath.rootPath().segment("hero"))
        val shared = task(a, b, path = b.path)
        val remaining = task(a)
        val graph = WorkGraph(work(shared, remaining))
        val sharedResult = result("shared")
        val remainingResult = result("remaining")
        graph.markInitialGroupsPending()
        graph.claimTasks().shouldContainExactlyInAnyOrder(shared, remaining)
        graph.completeTasks(listOf(shared to sharedResult))
        graph.processPendingGroups().shouldContainExactly(values(b, shared, sharedResult), WorkEvent.GroupSuccess(b, emptyList()))

        val duplicate = ExecutionGroupResult(null, listOf(error("duplicate")), Work.empty)
        assertThrows<IllegalArgumentException> { graph.completeTasks(listOf(shared to duplicate)) }

        graph.completeTasks(listOf(remaining to remainingResult))
        graph.claimTasks().shouldBeEmpty()
        graph.processPendingGroups().shouldContainExactly(
            values(a, remaining, remainingResult),
            WorkEvent.GroupSuccess(a, emptyList()),
            WorkEvent.Terminated,
        )
        assertFalse(graph.hasPendingGroups())
    }

    @Test
    fun `nested work extends an existing group before it succeeds`() {
        val group = group("A")
        val initial = task(group)
        val extension = task(group)
        val graph = WorkGraph(work(initial))
        val initialResult = result("initial", work(extension))
        val extensionResult = result("extension")
        graph.markInitialGroupsPending()
        graph.claimTasks()

        graph.completeTasks(listOf(initial to initialResult))

        graph.processPendingGroups().shouldBeEmpty()
        graph.claimTasks().shouldContainExactly(extension)
        graph.completeTasks(listOf(extension to extensionResult))
        graph.processPendingGroups().shouldContainExactly(
            values(group, initial, initialResult),
            values(group, extension, extensionResult),
            WorkEvent.GroupSuccess(group, emptyList()),
            WorkEvent.Terminated,
        )
    }

    @Test
    fun `new child groups become pending when their parent succeeds`() {
        val parent = group("parent")
        val child = group("child", parent)
        val initial = task(parent)
        val nested = task(child)
        val graph = WorkGraph(work(initial))
        val completed = result("parent", work(nested))
        graph.markInitialGroupsPending()
        graph.claimTasks()

        graph.completeTasks(listOf(initial to completed))

        graph.processPendingGroups().shouldContainExactly(values(parent, initial, completed), WorkEvent.GroupSuccess(parent, listOf(child)))
        graph.claimTasks().shouldContainExactly(nested)
    }

    @Test
    fun `failed groups allow discarding only tasks with no surviving group`() {
        val a = group("A")
        val b = group("B")
        val child = group("child", a)
        val failure = task(a)
        val unshared = task(a)
        val shared = task(a, b)
        val nested = task(child)
        val graph = WorkGraph(work(failure, unshared, shared, nested))
        val errors = listOf(error("failed"))
        graph.markInitialGroupsPending()
        graph.claimTasks().shouldContainExactlyInAnyOrder(failure, unshared, shared)

        graph.completeTasks(listOf(failure to ExecutionGroupResult(null, errors, Work.empty)))

        graph.processPendingGroups().shouldContainExactly(WorkEvent.GroupFailure(a, errors))
        assertTrue(graph.canDiscard(unshared))
        assertTrue(graph.canDiscard(nested))
        assertFalse(graph.canDiscard(shared))
        assertTrue(graph.hasPendingGroups())
    }

    @Test
    fun `nested work from shared tasks suppresses failed branches and completes surviving groups`() {
        val a = group("A")
        val b = group("B")
        val blockedChild = group("blocked child", a)
        val survivingChild = group("surviving child", b)
        val failure = task(a)
        val shared = task(a, b)
        val extension = task(a, b)
        val blocked = task(blockedChild)
        val nested = task(survivingChild)
        val graph = WorkGraph(work(failure, shared))
        val errors = listOf(error("failed"))
        val sharedResult = result("shared", work(extension, blocked, nested))
        val extensionResult = result("extension")
        val nestedResult = result("nested")
        graph.markInitialGroupsPending().shouldContainExactly(a, b)
        graph.claimTasks().shouldContainExactlyInAnyOrder(failure, shared)
        graph.completeTasks(listOf(failure to ExecutionGroupResult(null, errors, Work.empty)))
        graph.processPendingGroups().shouldContainExactly(WorkEvent.GroupFailure(a, errors))

        graph.completeTasks(listOf(shared to sharedResult))

        assertTrue(graph.canDiscard(blocked))
        assertFalse(graph.canDiscard(extension))
        graph.processPendingGroups().shouldBeEmpty()
        graph.claimTasks().shouldContainExactly(extension)
        graph.completeTasks(listOf(extension to extensionResult))
        graph.processPendingGroups().shouldContainExactly(
            values(b, shared, sharedResult),
            values(b, extension, extensionResult),
            WorkEvent.GroupSuccess(b, listOf(survivingChild)),
        )
        graph.claimTasks().shouldContainExactly(nested)
        graph.completeTasks(listOf(nested to nestedResult))
        graph.processPendingGroups().shouldContainExactly(
            values(survivingChild, nested, nestedResult),
            WorkEvent.GroupSuccess(survivingChild, emptyList()),
            WorkEvent.Terminated,
        )
        assertTrue(graph.canDiscard(blocked))
        assertFalse(graph.hasPendingGroups())
    }

    @Test
    fun `discardable task completions cannot add work to a surviving group`() {
        val a = group("A")
        val b = group("B")
        val failure = task(a)
        val late = task(a)
        val survivor = task(b)
        val extension = task(b)
        val graph = WorkGraph(work(failure, late, survivor))
        val errors = listOf(error("failed"))
        val survivorResult = result("survivor")
        graph.markInitialGroupsPending()
        graph.claimTasks().shouldContainExactlyInAnyOrder(failure, late, survivor)
        graph.completeTasks(listOf(failure to ExecutionGroupResult(null, errors, Work.empty)))
        graph.processPendingGroups().shouldContainExactly(WorkEvent.GroupFailure(a, errors))
        assertTrue(graph.canDiscard(late))

        graph.completeTasks(listOf(late to result("late", work(extension))))

        graph.claimTasks().shouldBeEmpty()
        graph.processPendingGroups().shouldBeEmpty()
        assertTrue(graph.canDiscard(late))
        graph.completeTasks(listOf(survivor to survivorResult))
        graph.processPendingGroups().shouldContainExactly(
            values(b, survivor, survivorResult),
            WorkEvent.GroupSuccess(b, emptyList()),
            WorkEvent.Terminated,
        )
        assertFalse(graph.hasPendingGroups())
    }

    @Test
    fun `failures in a completion batch cannot own shared values`() {
        val a = group("A")
        val b = group("B", path = ResultPath.rootPath().segment("hero"))
        val shared = task(a, b, path = b.path)
        val failure = task(b)
        val graph = WorkGraph(work(shared, failure))
        val completed = result("shared")
        val errors = listOf(error("failed"))
        graph.markInitialGroupsPending()
        graph.claimTasks()

        graph.completeTasks(listOf(shared to completed, failure to ExecutionGroupResult(null, errors, Work.empty)))

        graph.processPendingGroups().shouldContainExactly(
            WorkEvent.GroupFailure(b, errors),
            values(a, shared, completed),
            WorkEvent.GroupSuccess(a, emptyList()),
            WorkEvent.Terminated,
        )
    }

    @Test
    fun `group failures combine distinct errors from the completion batch`() {
        val group = group("A")
        val first = task(group)
        val second = task(group)
        val graph = WorkGraph(work(first, second))
        val firstError = error("first")
        val secondError = error("second")
        graph.markInitialGroupsPending()
        graph.claimTasks()

        graph.completeTasks(
            listOf(
                first to ExecutionGroupResult(null, listOf(firstError), Work.empty),
                second to ExecutionGroupResult(null, listOf(firstError, secondError), Work.empty),
            )
        )

        graph.processPendingGroups().shouldContainExactly(WorkEvent.GroupFailure(group, listOf(firstError, secondError)), WorkEvent.Terminated)
        graph.markInitialGroupsPending().shouldBeEmpty()
    }

    @Test
    fun `tasks without delivery groups are rejected`() {
        val task = ExecutionGroupTask(emptySet(), ResultPath.rootPath()) { result("field") }

        assertThrows<IllegalArgumentException> { WorkGraph(work(task)) }
    }

    @Test
    fun `unregistered tasks cannot complete`() {
        val graph = WorkGraph(Work.empty)
        val task = task(group("A"))

        assertThrows<IllegalArgumentException> { graph.completeTasks(listOf(task to result("field"))) }
    }

    @Test
    fun `unclaimed tasks cannot complete`() {
        val task = task(group("A"))
        val graph = WorkGraph(work(task))
        graph.markInitialGroupsPending()

        assertThrows<IllegalArgumentException> { graph.completeTasks(listOf(task to result("field"))) }
    }

    @Test
    fun `succeeded groups reject new tasks`() {
        val group = group("A")
        val initial = task(group)
        val other = task(group("B"))
        val graph = WorkGraph(work(initial, other))
        val initialResult = result("initial")
        graph.markInitialGroupsPending()
        graph.claimTasks()
        graph.completeTasks(listOf(initial to initialResult))
        graph.processPendingGroups().shouldContainExactly(values(group, initial, initialResult), WorkEvent.GroupSuccess(group, emptyList()))

        graph.markInitialGroupsPending().shouldBeEmpty()
        assertThrows<IllegalStateException> {
            graph.completeTasks(listOf(other to result("other", work(task(group)))))
        }
    }

    private fun group(
        label: String,
        parent: DeferDeliveryGroup? = null,
        path: ResultPath = ResultPath.rootPath(),
    ) = DeferDeliveryGroup(path, label, parent)

    private fun task(
        vararg groups: DeferDeliveryGroup,
        path: ResultPath = groups.first().path,
    ) = ExecutionGroupTask(groups.toSet(), path) { throw AssertionError("WorkGraph must not execute tasks") }

    private fun work(vararg tasks: ExecutionGroupTask) = Work(tasks.flatMap { it.deferredFragments }.toSet(), tasks.toList())

    private fun result(
        field: String,
        work: Work = Work.empty,
    ) = ExecutionGroupResult(mapOf(field to field), emptyList(), work)

    private fun values(
        group: DeferDeliveryGroup,
        task: ExecutionGroupTask,
        result: ExecutionGroupResult,
    ) = WorkEvent.GroupValues(group, listOf(GroupValue(task.path, checkNotNull(result.data), result.errors)))

    private fun error(message: String): GraphQLError = GraphQLError.newError().message(message).build()
}
