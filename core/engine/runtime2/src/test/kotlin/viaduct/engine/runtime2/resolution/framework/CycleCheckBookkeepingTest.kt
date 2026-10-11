@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution.framework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class CycleCheckBookkeepingTest {
    private val factories: List<() -> CycleCheckState> = listOf({ CycleCheckState.create() }, { CycleCheckState.createDeferred() })

    @Test
    fun `acyclic traces retain the same dependencies in both modes`() {
        val fixture = Fixture()
        val values = (0..4).map(fixture::node)
        val fieldChecker = fixture.node(0, CycleTaskKind.FIELD_CHECKER)
        val typeChecker = fixture.node(0, CycleTaskKind.TYPE_CHECKER)
        val writers = values + fieldChecker + typeChecker
        val reads = listOf(
            values[0] to values[1],
            values[0] to values[2],
            values[1] to values[3],
            values[2] to values[3],
            values[2] to values[4],
            values[3] to values[4],
            values[0] to fieldChecker,
            values[0] to typeChecker,
            fieldChecker to values[4],
            typeChecker to values[4],
        )
        val expectedEdges = reads.map { (reader, writer) -> reader.task to writer.task }.toSet()
        for (factory in factories) {
            // Probe every recorded dependency independently, so the first rejected eager edge
            // cannot affect subsequent probes. Both policies receive the exact same identities.
            for ((reader, writer) in reads) {
                val checker = factory()
                val earlyWriters = listOf(values[0], values[1], fieldChecker, typeChecker)
                earlyWriters.forEach { checker.registerWriter(it.slot, it.task) }
                repeat(3) { reads.forEach { (r, w) -> checker.cycleCheck(r.task, w.slot) } }
                (writers - earlyWriters.toSet()).forEach { checker.registerWriter(it.slot, it.task) }
                reads.forEach { (r, w) -> checker.cycleCheck(r.task, w.slot) }
                if (checker is DeferredCycleCheckState) assertNull(checker.findCycle())

                val cycle = closeCycle(checker, writer.task, reader.slot)
                val cycleEdges = cycle.zipWithNext().toSet()
                val closingEdge = writer.task to reader.task
                assertEquals(cycle.first(), cycle.last())
                assertTrue(closingEdge in cycleEdges)
                assertTrue(cycleEdges.all { it in expectedEdges || it == closingEdge })
            }
        }
    }

    @Test
    fun `both modes enforce write-once ownership and retain the original writer`() {
        val fixture = Fixture()
        val first = fixture.node(0)
        val second = fixture.node(1)
        for (factory in factories) {
            val checker = factory()
            checker.registerWriter(first.slot, first.task)
            checker.registerWriter(second.slot, second.task)
            val duplicate = assertFailsWith<IllegalStateException> { checker.registerWriter(second.slot, second.task) }
            val replacement = assertFailsWith<IllegalStateException> { checker.registerWriter(second.slot, first.task) }
            assertTrue(duplicate.message.orEmpty().startsWith("Writer already registered for slot:"))
            assertTrue(replacement.message.orEmpty().startsWith("Writer already registered for slot:"))
            checker.cycleCheck(first.task, second.slot)
            val cycle = closeCycle(checker, second.task, first.slot)
            assertEquals(setOf(first.task, second.task), cycle.dropLast(1).toSet())
            assertEquals(3, cycle.size)
        }
    }

    @Test
    fun `both modes preserve distinct roots with equal task paths`() {
        val fixture = Fixture()
        val first = fixture.node(0)
        val otherRoot = ObjectEngineResult.of(fixture.root.type)
        val otherTask = CycleTask.fieldResolver(otherRoot, first.task.path)
        val second = fixture.node(1)
        for (factory in factories) {
            val checker = factory()
            checker.registerWriter(first.slot, first.task)
            checker.registerWriter(second.slot, otherTask)
            checker.cycleCheck(otherTask, first.slot)
            if (checker is DeferredCycleCheckState) assertNull(checker.findCycle())
            val cycle = closeCycle(checker, first.task, second.slot)
            assertEquals(setOf(first.task, otherTask), cycle.dropLast(1).toSet())
            assertEquals(3, cycle.size)
        }
    }

    @Test
    fun `late writer rejection is eager while collection waits for explicit diagnosis`() {
        val fixture = Fixture()
        val node = fixture.node(0)
        val eager = CycleCheckState.create()
        val deferred = CycleCheckState.createDeferred()
        listOf(eager, deferred).forEach { checker ->
            repeat(3) { checker.cycleCheck(node.task, node.slot) }
        }
        assertNull(deferred.findCycle())
        val failure = assertFailsWith<ResolverReadCycleException> { eager.registerWriter(node.slot, node.task) }
        assertEquals(listOf(node.task, node.task), failure.cycle)
        // Rejection preserves the installed writer and edge, including duplicate-edge rejection.
        assertFailsWith<IllegalStateException> { eager.registerWriter(node.slot, node.task) }
        assertFailsWith<ResolverReadCycleException> { eager.cycleCheck(node.task, node.slot) }

        deferred.registerWriter(node.slot, node.task)
        repeat(3) { deferred.cycleCheck(node.task, node.slot) }
        assertEquals(listOf(node.task, node.task), deferred.findCycle())
    }

    @Test
    fun `deferred reads and late writers perform no reachability endpoint comparisons`() {
        val world = TestWorld.fromSDL("type Query { first: Int second: Int }").assumptions
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        var endpointComparisons = 0
        val field = world.schema.requireObjectField("Query", "second")
        val countedField = object : ViaductSchema.ObjectField by field {
            override fun equals(other: Any?): Boolean {
                endpointComparisons++
                return field == other
            }

            override fun hashCode(): Int = field.hashCode()
        }
        val firstKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "first"), emptyMap())
        val secondKey = ObjectEngineResult.GroundKey.of(countedField, emptyMap())
        val reader = CycleTask.fieldResolver(root, listOf(firstKey))
        val writer = CycleTask.fieldResolver(root, listOf(secondKey))
        val slot = CycleSlot.value(root.reserveCell(secondKey))
        for (registerEarly in listOf(true, false)) {
            val checker = CycleCheckState.createDeferred()
            endpointComparisons = 0
            if (registerEarly) checker.registerWriter(slot, writer)
            repeat(3) { checker.cycleCheck(reader, slot) }
            if (!registerEarly) checker.registerWriter(slot, writer)
            repeat(3) { checker.cycleCheck(reader, slot) }
            // This witness observes the endpoint comparison made by eager pathFrom, as in
            // CycleCheckStateTest, without adding a production search counter.
            assertEquals(0, endpointComparisons)
        }
    }

    @Test
    fun `shared policy hook sees installed edges under the same monitor`() {
        val fixture = Fixture()
        val first = fixture.node(0)
        val second = fixture.node(1)
        val graph = RecordingGraph()
        graph.registerWriter(first.slot, first.task)
        repeat(3) { graph.cycleCheck(first.task, second.slot) }
        assertTrue(graph.attempts.isEmpty())
        graph.registerWriter(second.slot, second.task)
        repeat(3) { graph.cycleCheck(first.task, second.slot) }
        assertEquals(listOf(true, false, false, false), graph.attempts.map { it.added })
        assertEquals(setOf(first.task to second.task), graph.edges())
    }

    @Test
    fun `policy failure retains the writer and offending edge and stops pending reader hookup`() {
        val fixture = Fixture()
        val readers = (0..2).map(fixture::node)
        val writer = fixture.node(3)
        val graph = RecordingGraph(reject = true)
        readers.forEach { graph.cycleCheck(it.task, writer.slot) }
        assertFailsWith<PolicyFailure> { graph.registerWriter(writer.slot, writer.task) }
        assertEquals(1, graph.attempts.size)
        val attempted = graph.attempts.single()
        assertTrue(attempted.added)
        assertTrue(attempted.reader in readers.map { it.task })
        assertEquals(setOf(attempted.reader to writer.task), graph.edges())
        assertFailsWith<IllegalStateException> { graph.registerWriter(writer.slot, writer.task) }
        assertEquals(1, graph.attempts.size)
    }

    private fun closeCycle(
        checker: CycleCheckState,
        reader: CycleTask,
        slot: CycleSlot,
    ): List<CycleTask> =
        if (checker is DeferredCycleCheckState) {
            checker.cycleCheck(reader, slot)
            checkNotNull(checker.findCycle())
        } else {
            assertFailsWith<ResolverReadCycleException> { checker.cycleCheck(reader, slot) }.cycle
        }

    private class PolicyFailure : IllegalStateException()

    private data class Attempt(val reader: CycleTask, val writer: CycleTask, val added: Boolean)

    private class RecordingGraph(private val reject: Boolean = false) : AbstractCycleCheckState() {
        val attempts = mutableListOf<Attempt>()

        override fun onDependencyRecorded(
            reader: CycleTask,
            writer: CycleTask,
            added: Boolean,
        ) {
            assertTrue(Thread.holdsLock(this))
            assertTrue(writer in readsByReader[reader].orEmpty())
            attempts += Attempt(reader, writer, added)
            if (reject) throw PolicyFailure()
        }

        @Synchronized
        fun edges(): Set<Pair<CycleTask, CycleTask>> = readsByReader.flatMap { (reader, writers) -> writers.map { reader to it } }.toSet()
    }

    private class Node(val task: CycleTask, val slot: CycleSlot)

    private class Fixture {
        private val world = TestWorld.fromSDL("type Query { field(index: Int): Int }").assumptions
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        private val cells = mutableMapOf<Int, EngineResultCell>()

        fun node(index: Int): Node = node(index, CycleTaskKind.FIELD_RESOLVER)

        fun node(
            index: Int,
            kind: CycleTaskKind
        ): Node {
            val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "field"), mapOf("index" to index))
            val cell = cells.getOrPut(index) { root.reserveCell(key) }
            return when (kind) {
                CycleTaskKind.FIELD_RESOLVER -> Node(CycleTask.fieldResolver(root, listOf(key)), CycleSlot.value(cell))
                CycleTaskKind.FIELD_CHECKER -> Node(CycleTask.fieldChecker(root, listOf(key)), CycleSlot.fieldChecker(cell))
                CycleTaskKind.TYPE_CHECKER -> Node(CycleTask.typeChecker(root, emptyList()), CycleSlot.typeChecker(root))
            }
        }
    }
}
