package semantics.shared

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import model.EngineResultCell
import model.ObjectEngineResult
import model.PathComponent
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld

class CycleCheckStateTest {
    @Test
    fun `acyclic reads are retained without failure`() {
        val fixture = Fixture()

        fixture.register("second")
        fixture.register("third")
        fixture.checker.cycleCheck(
            reader = fixture.task("first"),
            slot = fixture.slot("second"),
        )
        fixture.checker.cycleCheck(
            reader = fixture.task("second"),
            slot = fixture.slot("third"),
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
                    slot = fixture.slot("first"),
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
            slot = fixture.slot("first"),
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
            slot = fixture.slot("second"),
        )
        fixture.checker.cycleCheck(
            reader = fixture.task("second"),
            slot = fixture.slot("third"),
        )

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.checker.cycleCheck(
                    reader = fixture.task("third"),
                    slot = fixture.slot("first"),
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
                slot = fixture.target.getCell(key).valueCycleSlot,
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
                slot = fixture.slot("second"),
            )
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
            slot = fixture.slot("first", CycleSlotKind.VALUE),
            writer = fixture.task("first", CycleTaskKind.FIELD_RESOLVER),
        )
        fixture.checker.registerWriter(
            slot = fixture.slot("first", CycleSlotKind.FIELD_CHECKER),
            writer = fixture.task("first", CycleTaskKind.FIELD_CHECKER),
        )
        fixture.checker.registerWriter(
            slot = fixture.target.typeCheckerCycleSlot,
            writer = fixture.task("first", CycleTaskKind.TYPE_CHECKER),
        )
    }

    @Test
    fun `cross-slot resolver-checker cycle is rejected`() {
        val fixture = Fixture()
        val resolver = fixture.task("first", CycleTaskKind.FIELD_RESOLVER)
        val checker = fixture.task("first", CycleTaskKind.FIELD_CHECKER)
        fixture.checker.registerWriter(fixture.slot("first", CycleSlotKind.VALUE), resolver)
        fixture.checker.registerWriter(fixture.slot("first", CycleSlotKind.FIELD_CHECKER), checker)
        fixture.checker.cycleCheck(resolver, fixture.slot("first", CycleSlotKind.FIELD_CHECKER))

        val failure =
            assertFailsWith<ResolverReadCycleException> {
                fixture.checker.cycleCheck(checker, fixture.slot("first", CycleSlotKind.VALUE))
            }

        assertEquals(listOf(checker, resolver, checker), failure.cycle)
    }

    @Test
    fun `equal paths under different Query roots are different tasks`() {
        val fixture = Fixture()
        val otherRoot = ObjectEngineResult.of(fixture.world.schema.requireQueryTypeDef(), mutable = true)
        val writer = fixture.task("first")
        val reader = fixture.task("first", root = otherRoot)
        fixture.register("first")

        fixture.checker.cycleCheck(reader, fixture.slot("first"))

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
                fixture.task("first") to fixture.slot("second"),
                fixture.task("second") to fixture.slot("first"),
            )
        val workers =
            checks.map { (reader, slot) ->
                thread {
                    ready.countDown()
                    start.await()
                    try {
                        fixture.checker.cycleCheck(reader, slot)
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
    fun `NOP cycle checker ignores writer registration and cycle checks`() {
        val fixture = Fixture()
        val checker = CycleCheckState.createNOP()

        checker.registerWriter(
            slot = fixture.slot("first"),
            writer = fixture.task("first"),
        )
        checker.cycleCheck(
            reader = fixture.task("first"),
            slot = fixture.slot("first"),
        )
    }

    private class Fixture {
        val world =
            TestWorld
                .fromSDL(
                    """
                    type Query {
                      first: String!
                      second: String!
                      third: String!
                    }
                    """.trimIndent(),
                ).assumptions
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

        fun slot(
            name: String,
            kind: CycleSlotKind = CycleSlotKind.VALUE,
        ): CycleSlot = CycleSlot(kind, cell(name))

        fun register(name: String) {
            checker.registerWriter(
                slot = slot(name),
                writer = task(name),
            )
        }
    }
}
