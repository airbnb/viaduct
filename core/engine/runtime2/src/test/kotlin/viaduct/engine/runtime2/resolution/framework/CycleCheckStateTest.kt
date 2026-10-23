package viaduct.engine.runtime2.resolution.framework

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class CycleCheckStateTest {
    @Test
    fun `acyclic reads are retained without failure`() {
        val fixture = Fixture()

        fixture.register("second")
        fixture.register("third")
        fixture.checker.cycleCheck(
            reader = fixture.task("first"),
            owner = fixture.cell("second"),
            kind = CycleSlotKind.VALUE,
        )
        fixture.checker.cycleCheck(
            reader = fixture.task("second"),
            owner = fixture.cell("third"),
            kind = CycleSlotKind.VALUE,
        )
    }

    @Test
    fun `direct self-cycle reports its complete path`() {
        val fixture = Fixture()
        fixture.register("first")

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.checker.cycleCheck(
                    reader = fixture.task("first"),
                    owner = fixture.cell("first"),
                    kind = CycleSlotKind.VALUE,
                )
            }

        assertEquals(
            listOf(fixture.task("first"), fixture.task("first")),
            failure.cycle,
        )
    }

    @Test
    fun `read recorded before writer registration still detects a cycle`() {
        val fixture = Fixture()
        fixture.checker.cycleCheck(
            reader = fixture.task("first"),
            owner = fixture.cell("first"),
            kind = CycleSlotKind.VALUE,
        )

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.register("first")
            }

        assertEquals(
            listOf(fixture.task("first"), fixture.task("first")),
            failure.cycle,
        )
    }

    @Test
    fun `multi-hop cycle reports dependency order`() {
        val fixture = Fixture()
        fixture.register("first")
        fixture.register("second")
        fixture.register("third")
        fixture.checker.cycleCheck(
            reader = fixture.task("first"),
            owner = fixture.cell("second"),
            kind = CycleSlotKind.VALUE,
        )
        fixture.checker.cycleCheck(
            reader = fixture.task("second"),
            owner = fixture.cell("third"),
            kind = CycleSlotKind.VALUE,
        )

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.checker.cycleCheck(
                    reader = fixture.task("third"),
                    owner = fixture.cell("first"),
                    kind = CycleSlotKind.VALUE,
                )
            }

        assertEquals(
            listOf(
                fixture.task("third"),
                fixture.task("first"),
                fixture.task("second"),
                fixture.task("third"),
            ),
            failure.cycle,
        )
    }

    @Test
    fun `completed deferred slot still contributes a read edge`() {
        val fixture = Fixture()
        val key = fixture.key("first")
        fixture.target.reserveCell(key).also { cell ->
            cell.value.claim()
            cell.setActivated(true)
            cell.value.complete("complete")
        }
        fixture.register("first")

        assertFailsWith<ResolverReadCycleException> {
            fixture.checker.cycleCheck(
                reader = fixture.task("first"),
                owner = fixture.target.getCell(key),
                kind = CycleSlotKind.VALUE,
            )
        }
    }

    @Test
    fun `recording the same acyclic edge repeatedly is harmless`() {
        val fixture = Fixture()
        fixture.register("second")

        repeat(2) {
            fixture.checker.cycleCheck(
                reader = fixture.task("first"),
                owner = fixture.cell("second"),
                kind = CycleSlotKind.VALUE,
            )
        }
    }

    @Test
    fun `duplicate accepted edges do not repeat reachability searches`() {
        val fixture = Fixture()
        var endpointComparisons = 0
        val field = fixture.world.schema.requireObjectField("Query", "second")
        val countedField =
            object : ViaductSchema.ObjectField by field {
                override fun equals(other: Any?): Boolean {
                    endpointComparisons++
                    return field == other
                }

                override fun hashCode(): Int = field.hashCode()
            }
        val writer =
            CycleTask.fieldResolver(
                fixture.target,
                listOf(ObjectEngineResult.GroundKey.of(countedField, emptyMap())),
            )
        val reader = fixture.task("first")
        val slotOwner = fixture.cell("second")
        fixture.checker.registerWriter(slotOwner, CycleSlotKind.VALUE, writer)
        fixture.register("first")

        // pathFrom compares its endpoints before traversing. Observe that comparison without
        // adding instrumentation to production or relying on the task's hash implementation.
        fixture.checker.cycleCheck(reader, slotOwner, CycleSlotKind.VALUE)
        assertTrue(endpointComparisons > 0)
        endpointComparisons = 0
        repeat(10) { fixture.checker.cycleCheck(reader, slotOwner, CycleSlotKind.VALUE) }
        assertEquals(0, endpointComparisons)

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.checker.cycleCheck(writer, fixture.cell("first"), CycleSlotKind.VALUE)
            }
        assertEquals(listOf(writer, reader, writer), failure.cycle)
    }

    @Test
    fun `repeated attempts to insert a rejected edge still report the cycle`() {
        val fixture = Fixture()
        fixture.register("first")
        fixture.register("second")
        fixture.checker.cycleCheck(fixture.task("first"), fixture.cell("second"), CycleSlotKind.VALUE)

        repeat(3) {
            val failure =
                assertFailsWith<ResolverReadCycleException> {
                    fixture.checker.cycleCheck(fixture.task("second"), fixture.cell("first"), CycleSlotKind.VALUE)
                }
            assertEquals(listOf(fixture.task("second"), fixture.task("first"), fixture.task("second")), failure.cycle)
        }
    }

    @Test
    fun `accepted edge is rechecked after another edge was rejected`() {
        val fixture = Fixture()
        fixture.register("first")
        fixture.register("second")
        fixture.checker.cycleCheck(fixture.task("first"), fixture.cell("second"), CycleSlotKind.VALUE)
        assertFailsWith<ResolverReadCycleException> {
            fixture.checker.cycleCheck(fixture.task("second"), fixture.cell("first"), CycleSlotKind.VALUE)
        }

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.checker.cycleCheck(fixture.task("first"), fixture.cell("second"), CycleSlotKind.VALUE)
            }
        assertEquals(listOf(fixture.task("first"), fixture.task("second"), fixture.task("first")), failure.cycle)
    }

    @Test
    fun `repeated direct self-cycle still fails`() {
        val fixture = Fixture()
        fixture.register("first")

        repeat(3) {
            val failure =
                assertFailsWith<ResolverReadCycleException> {
                    fixture.checker.cycleCheck(fixture.task("first"), fixture.cell("first"), CycleSlotKind.VALUE)
                }
            assertEquals(listOf(fixture.task("first"), fixture.task("first")), failure.cycle)
        }
    }

    @Test
    fun `writer registration is write-once per exact slot`() {
        val fixture = Fixture()
        fixture.register("first")

        assertFailsWith<IllegalStateException> {
            fixture.register("first")
        }
    }

    @Test
    fun `one cell has independent value and checker writers`() {
        val fixture = Fixture()

        fixture.checker.registerWriter(
            owner = fixture.cell("first"),
            kind = CycleSlotKind.VALUE,
            writer = fixture.task("first", CycleTaskKind.FIELD_RESOLVER),
        )
        fixture.checker.registerWriter(
            owner = fixture.cell("first"),
            kind = CycleSlotKind.FIELD_CHECKER,
            writer = fixture.task("first", CycleTaskKind.FIELD_CHECKER),
        )
        fixture.checker.registerWriter(
            owner = fixture.target,
            kind = CycleSlotKind.TYPE_CHECKER,
            writer = fixture.task("first", CycleTaskKind.TYPE_CHECKER),
        )
    }

    @Test
    fun `cross-slot resolver-checker cycle is rejected`() {
        val fixture = Fixture()
        val resolver = fixture.task("first", CycleTaskKind.FIELD_RESOLVER)
        val checker = fixture.task("first", CycleTaskKind.FIELD_CHECKER)
        fixture.checker.registerWriter(fixture.cell("first"), CycleSlotKind.VALUE, resolver)
        fixture.checker.registerWriter(fixture.cell("first"), CycleSlotKind.FIELD_CHECKER, checker)
        fixture.checker.cycleCheck(resolver, fixture.cell("first"), CycleSlotKind.FIELD_CHECKER)

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.checker.cycleCheck(checker, fixture.cell("first"), CycleSlotKind.VALUE)
            }

        assertEquals(listOf(checker, resolver, checker), failure.cycle)
    }

    @Test
    fun `task identity hash is computed once at construction`() {
        val fixture = Fixture()
        var fieldHashes = 0
        val field = fixture.world.schema.requireObjectField("Query", "first")
        val countedField =
            object : ViaductSchema.ObjectField by field {
                override fun hashCode(): Int {
                    fieldHashes++
                    return field.hashCode()
                }
            }
        val key = ObjectEngineResult.GroundKey.of(countedField, emptyMap())
        val task = CycleTask.fieldResolver(fixture.target, listOf(key))
        val equalTask = CycleTask.fieldResolver(fixture.target, listOf(key))
        assertTrue(fieldHashes > 0)
        val expectedHash = task.hashCode()
        fieldHashes = 0

        repeat(10) {
            assertEquals(expectedHash, task.hashCode())
            assertEquals(expectedHash, equalTask.hashCode())
            assertEquals(equalTask, task)
        }
        assertEquals(0, fieldHashes)
        assertEquals(listOf(key), task.path)
    }

    @Test
    fun `equal paths under different Query roots are different tasks`() {
        val fixture = Fixture()
        val otherRoot = ObjectEngineResult.of(fixture.world.schema.requireQueryTypeDef(), mutable = true)
        val writer = fixture.task("first")
        val reader = fixture.task("first", root = otherRoot)
        fixture.register("first")

        fixture.checker.cycleCheck(reader, fixture.cell("first"), CycleSlotKind.VALUE)

        assertTrue(writer != reader)
    }

    @Test
    fun `concurrent edge insertion detects a newly closed cycle`() {
        val fixture = Fixture()
        fixture.register("first")
        fixture.register("second")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val checks =
            listOf(
                Triple(fixture.task("first"), fixture.cell("second"), CycleSlotKind.VALUE),
                Triple(fixture.task("second"), fixture.cell("first"), CycleSlotKind.VALUE),
            )
        val workers =
            checks.map { (reader, owner, kind) ->
                thread {
                    ready.countDown()
                    start.await()
                    try {
                        fixture.checker.cycleCheck(reader, owner, kind)
                    } catch (throwable: Throwable) {
                        failures += throwable
                    }
                }
            }

        ready.await()
        start.countDown()
        workers.forEach(Thread::join)

        assertTrue(failures.isNotEmpty())
        failures.forEach { failure ->
            assertIs<ResolverReadCycleException>(failure)
            assertEquals(failure.cycle.first(), failure.cycle.last())
            assertEquals(
                setOf(fixture.task("first"), fixture.task("second")),
                failure.cycle.toSet(),
            )
        }
    }

    @Test
    fun `overlapping reader and writer registration retains the dependency`() {
        val world = Fixture().world
        repeat(128) { iteration ->
            val fixture = Fixture(world)
            fixture.register("first")
            val reader = fixture.task("first")
            val writer = fixture.task("second")
            val slotOwner = fixture.cell("second")
            val actions =
                listOf<() -> Unit>(
                    { fixture.checker.cycleCheck(reader, slotOwner, CycleSlotKind.VALUE) },
                    { fixture.checker.registerWriter(slotOwner, CycleSlotKind.VALUE, writer) },
                )
            val failures = concurrently(if (iteration % 2 == 0) actions else actions.reversed())
            assertTrue(failures.isEmpty(), "Concurrent registration failed: $failures")

            // Both operations returning is insufficient: this closes a cycle only if the
            // concurrently registered first -> second dependency was actually recorded.
            val failure =
                assertFailsWith<ResolverReadCycleException> {
                    fixture.checker.cycleCheck(writer, fixture.cell("first"), CycleSlotKind.VALUE)
                }
            assertEquals(listOf(writer, reader, writer), failure.cycle)
        }
    }

    @Test
    fun `multiple concurrent readers are connected to their writer`() {
        val world = Fixture().world
        repeat(64) { iteration ->
            val fixture = Fixture(world)
            fixture.register("first")
            fixture.register("second")
            val first = fixture.task("first")
            val second = fixture.task("second")
            val writer = fixture.task("third")
            val slotOwner = fixture.cell("third")
            val actions =
                listOf<() -> Unit>(
                    { fixture.checker.cycleCheck(first, slotOwner, CycleSlotKind.VALUE) },
                    { fixture.checker.cycleCheck(second, slotOwner, CycleSlotKind.VALUE) },
                    { fixture.checker.registerWriter(slotOwner, CycleSlotKind.VALUE, writer) },
                )
            val failures = concurrently(if (iteration % 2 == 0) actions else actions.reversed())
            assertTrue(failures.isEmpty(), "Concurrent registration failed: $failures")

            for (name in listOf("first", "second")) {
                val failure =
                    assertFailsWith<ResolverReadCycleException> {
                        fixture.checker.cycleCheck(writer, fixture.cell(name), CycleSlotKind.VALUE)
                    }
                assertEquals(listOf(writer, fixture.task(name), writer), failure.cycle)
            }
        }
    }

    @Test
    fun `concurrent duplicate reads retain one acyclic dependency`() {
        val world = Fixture().world
        repeat(32) {
            val fixture = Fixture(world)
            fixture.register("first")
            fixture.register("second")
            val reader = fixture.task("first")
            val writer = fixture.task("second")
            val slotOwner = fixture.cell("second")
            val failures =
                concurrently(List(4) { { fixture.checker.cycleCheck(reader, slotOwner, CycleSlotKind.VALUE) } })
            assertTrue(failures.isEmpty(), "Concurrent duplicate read failed: $failures")

            val failure =
                assertFailsWith<ResolverReadCycleException> {
                    fixture.checker.cycleCheck(writer, fixture.cell("first"), CycleSlotKind.VALUE)
                }
            assertEquals(listOf(writer, reader, writer), failure.cycle)
        }
    }

    @Test
    fun `concurrent writer registration still rejects duplicate ownership`() {
        val fixture = Fixture()
        val slotOwner = fixture.cell("first")
        val writer = fixture.task("first")
        val failures =
            concurrently(List(2) { { fixture.checker.registerWriter(slotOwner, CycleSlotKind.VALUE, writer) } })

        assertEquals(1, failures.size)
        val failure = assertIs<IllegalStateException>(failures.single())
        assertTrue(failure.message.orEmpty().startsWith("Writer already registered for slot:"))
    }

    @Test
    fun `NOP cycle checker ignores writer registration and cycle checks`() {
        val fixture = Fixture()
        val checker = CycleCheckState.createNOP()

        checker.registerWriter(
            owner = fixture.cell("first"),
            kind = CycleSlotKind.VALUE,
            writer = fixture.task("first"),
        )
        checker.cycleCheck(
            reader = fixture.task("first"),
            owner = fixture.cell("first"),
            kind = CycleSlotKind.VALUE,
        )
    }

    private fun concurrently(actions: List<() -> Unit>): List<Throwable> {
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val workers =
            actions.mapIndexed { index, action ->
                thread(isDaemon = true) {
                    ready.countDown()
                    try {
                        check(start.await(10, TimeUnit.SECONDS)) { "Timed out waiting for concurrent start" }
                        if (index % 2 == 0) Thread.yield()
                        action()
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
        val allReady = ready.await(10, TimeUnit.SECONDS)
        start.countDown()
        workers.forEach { it.join(10_000) }
        assertTrue(allReady, "Workers did not reach the start barrier")
        assertTrue(workers.none { it.isAlive }, "Concurrent graph update did not finish")
        return failures.toList()
    }

    private class Fixture(
        val world: Assumptions =
            TestWorld
                .fromSDL(
                    """
                    type Query {
                      first: String!
                      second: String!
                      third: String!
                    }
                    """.trimIndent(),
                ).assumptions,
    ) {
        val target = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        val checker = CycleCheckState.create()

        fun key(name: String): ObjectEngineResult.GroundKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", name),
                emptyMap(),
            )

        fun path(name: String): List<PathComponent> = listOf(key(name))

        fun task(
            name: String,
            kind: CycleTaskKind = CycleTaskKind.FIELD_RESOLVER,
            root: ObjectEngineResult = target,
        ): CycleTask = CycleTask(kind, root, path(name))

        fun cell(name: String): EngineResultCell = target.reserveCell(key(name))

        fun register(name: String) {
            checker.registerWriter(
                owner = cell(name),
                kind = CycleSlotKind.VALUE,
                writer = task(name),
            )
        }
    }
}
