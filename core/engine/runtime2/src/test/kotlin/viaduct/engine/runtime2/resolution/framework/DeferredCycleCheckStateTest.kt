@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution.framework

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld

class DeferredCycleCheckStateTest {
    @Test
    fun `empty and converging acyclic graphs have no cycle`() {
        val fixture = Fixture()
        assertNull(fixture.checker.findCycle())
        val nodes = (0..3).map(fixture::node)
        nodes.forEach(fixture::register)
        fixture.read(nodes[0], nodes[1])
        fixture.read(nodes[0], nodes[2])
        fixture.read(nodes[1], nodes[3])
        fixture.read(nodes[2], nodes[3])
        assertNull(fixture.checker.findCycle())
    }

    @Test
    fun `self-cycle is collected without throwing and diagnosed repeatedly`() {
        val fixture = Fixture()
        val node = fixture.node(0)
        fixture.register(node)
        repeat(10) { fixture.read(node, node) }
        repeat(3) { assertEquals(listOf(node.task, node.task), fixture.checker.findCycle()) }
    }

    @Test
    fun `multi-hop cycle survives duplicate edges and pending writer registration`() {
        val fixture = Fixture()
        val nodes = (0..2).map(fixture::node)
        fixture.register(nodes[0])
        fixture.register(nodes[1])
        repeat(10) { fixture.read(nodes[0], nodes[1]) }
        fixture.read(nodes[1], nodes[2])
        fixture.read(nodes[2], nodes[0])
        assertNull(fixture.checker.findCycle())
        fixture.register(nodes[2])
        val cycle = checkNotNull(fixture.checker.findCycle())
        assertEquals(cycle.first(), cycle.last())
        assertEquals(nodes.map { it.task }.toSet(), cycle.dropLast(1).toSet())
        assertEquals(4, cycle.size)
    }

    @Test
    fun `completed slot dependencies remain historical evidence`() {
        val fixture = Fixture()
        val first = fixture.node(0)
        val second = fixture.node(1)
        fixture.register(first)
        fixture.register(second)
        first.cell.value.complete(1)
        second.cell.value.complete(2)
        fixture.read(first, second)
        fixture.read(second, first)
        val cycle = checkNotNull(fixture.checker.findCycle())
        assertEquals(3, cycle.size)
        assertEquals(cycle.first(), cycle.last())
    }

    @Test
    fun `denial can release a recorded dependency and allow a historical cycle to finish`() =
        runTest {
            val fixture = Fixture()
            val first = fixture.node(0)
            val second = fixture.node(1)
            fixture.register(first)
            fixture.register(second)
            first.cell.fieldCheckerResult.complete(null)
            val denial = object : CheckerResult.Error {
                override val error: Exception = IllegalStateException("denied")

                override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

                override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
            }
            second.cell.fieldCheckerResult.complete(denial)
            assertIs<ErrorEngineResult>(second.cell.materializeCheckedValueForResolver(null, first.task, fixture.checker))
            first.cell.value.complete(1)
            assertEquals(1, first.cell.materializeCheckedValueForResolver(null, second.task, fixture.checker))
            second.cell.value.complete(2)
            assertTrue(first.cell.value.isCompleted && second.cell.value.isCompleted)
            assertEquals(3, checkNotNull(fixture.checker.findCycle()).size)
        }

    @Test
    fun `writer uniqueness is still enforced during collection`() {
        val fixture = Fixture()
        val node = fixture.node(0)
        fixture.register(node)
        assertFailsWith<IllegalStateException> { fixture.register(node) }
    }

    @Test
    fun `slot and task kinds remain independent`() {
        val fixture = Fixture()
        val first = fixture.node(0)
        val second = fixture.node(1)
        fixture.register(first)
        fixture.register(second)
        val fieldChecker = CycleTask.fieldChecker(fixture.root, first.task.path)
        val typeChecker = CycleTask.typeChecker(fixture.root, emptyList())
        fixture.checker.registerWriter(CycleSlot.fieldChecker(first.cell), fieldChecker)
        fixture.checker.registerWriter(CycleSlot.typeChecker(fixture.root), typeChecker)
        fixture.checker.cycleCheck(first.task, CycleSlot.fieldChecker(first.cell))
        fixture.checker.cycleCheck(first.task, CycleSlot.typeChecker(fixture.root))
        fixture.checker.cycleCheck(fieldChecker, second.slot)
        fixture.checker.cycleCheck(typeChecker, second.slot)
        assertNull(fixture.checker.findCycle())
        fixture.checker.cycleCheck(second.task, first.slot)
        assertTrue(checkNotNull(fixture.checker.findCycle()).size >= 4)
    }

    @Test
    fun `equal paths under distinct roots do not create a self-cycle`() {
        val fixture = Fixture()
        val node = fixture.node(0)
        val otherRoot = ObjectEngineResult.of(fixture.root.type)
        fixture.register(node)
        fixture.checker.cycleCheck(CycleTask.fieldResolver(otherRoot, node.task.path), node.slot)
        assertNull(fixture.checker.findCycle())
    }

    @Test
    fun `deep graphs are scanned iteratively and return an exact closed cycle`() {
        val fixture = Fixture()
        val nodes = (0 until 20_000).map(fixture::node)
        nodes.forEach(fixture::register)
        for (index in 1 until nodes.size) fixture.read(nodes[index - 1], nodes[index])
        assertNull(fixture.checker.findCycle())
        fixture.read(nodes.last(), nodes.first())
        val cycle = checkNotNull(fixture.checker.findCycle())
        assertEquals(nodes.size + 1, cycle.size)
        assertEquals(cycle.first(), cycle.last())
        val successors = nodes.indices.associate { index -> nodes[index].task to nodes[(index + 1) % nodes.size].task }
        cycle.zipWithNext().forEach { (reader, writer) -> assertEquals(successors[reader], writer) }
    }

    @Test
    fun `overlapping reads and writer registration retain every dependency`() {
        val fixture = Fixture()
        repeat(128) { index ->
            val first = fixture.node(index * 2)
            val second = fixture.node(index * 2 + 1)
            val checker = CycleCheckState.createDeferred()
            checker.registerWriter(first.slot, first.task)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val actions = listOf(
                { checker.cycleCheck(first.task, second.slot) },
                { checker.registerWriter(second.slot, second.task) },
            )
            val failures = ConcurrentLinkedQueue<Throwable>()
            actions.forEach { action ->
                thread(isDaemon = true) {
                    try {
                        check(start.await(10, TimeUnit.SECONDS))
                        action()
                    } catch (failure: Throwable) {
                        failures.add(failure)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), failures.toString())
            assertNull(checker.findCycle())
            // Closing the reverse edge proves that the overlapping dependency was recorded.
            checker.cycleCheck(second.task, first.slot)
            val cycle = checkNotNull(checker.findCycle())
            assertEquals(3, cycle.size)
            assertEquals(cycle.first(), cycle.last())
            assertEquals(setOf(first.task, second.task), cycle.dropLast(1).toSet())
        }
    }

    private class Node(
        val task: CycleTask,
        val cell: EngineResultCell,
    ) {
        val slot = CycleSlot.value(cell)
    }

    private class Fixture {
        private val world = TestWorld.fromSDL("type Query { field(index: Int): Int }").assumptions
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        val checker = CycleCheckState.createDeferred()

        fun node(index: Int): Node {
            val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "field"), mapOf("index" to index))
            val cell = root.reserveCell(key)
            cell.value.claim()
            cell.setActivated(true)
            return Node(CycleTask.fieldResolver(root, listOf(key)), cell)
        }

        fun register(node: Node) = checker.registerWriter(node.slot, node.task)

        fun read(
            reader: Node,
            writer: Node
        ) = checker.cycleCheck(reader.task, writer.slot)
    }
}
