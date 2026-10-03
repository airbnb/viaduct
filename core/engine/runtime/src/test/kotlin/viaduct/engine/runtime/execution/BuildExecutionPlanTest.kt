package viaduct.engine.runtime.execution

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.EngineSchema
import viaduct.engine.runtime.execution.QueryPlan.Field

class BuildExecutionPlanTest {
    private val schema = EngineSchema("type Query { a: Int, b: Int, c: Int, d: Int }".asSchema)
    private val sourceFields by lazy {
        buildPlan("{ a b c d }", schema).selectionSet.selections.filterIsInstance<Field>().associateBy { it.resultKey }
    }

    @Test
    fun `empty input creates no execution groups`() {
        for (parentUsages in listOf(emptySet(), setOf(usage("parent")))) {
            val result = BuildExecutionPlan(emptyMap(), parentUsages)

            assertTrue(result.collectedFieldsMap.isEmpty())
            assertTrue(result.newCollectedFieldsMaps.isEmpty())
        }
    }

    @Test
    fun `omitted parent usages mean the non-deferred context`() {
        val input = linkedMapOf("c" to field("c", null), "a" to field("a", null), "b" to field("b", null))

        val result = BuildExecutionPlan(input)

        assertPlan(result, listOf("c", "a", "b"), emptyMap())
        assertEquals(result, BuildExecutionPlan(input, emptySet()))
        input.forEach { (key, value) -> assertSame(value, result.collectedFieldsMap.getValue(key)) }
    }

    @Test
    fun `partitions immediate unique and shared deferred fields`() {
        val a = usage("A")
        val b = usage("B")
        val input = linkedMapOf(
            "a" to field("a", a),
            "b" to field("b", b),
            "c" to field("c", a, b),
            "d" to field("d", null),
        )

        val result = BuildExecutionPlan(input)

        assertPlan(result, listOf("d"), mapOf(setOf(a) to listOf("a"), setOf(b) to listOf("b"), setOf(a, b) to listOf("c")))
        result.newCollectedFieldsMaps.values.forEach { group ->
            group.forEach { (key, value) -> assertSame(input.getValue(key), value) }
        }
    }

    @Test
    fun `preserves encounter order within each partition`() {
        val a = usage("A")
        val input = linkedMapOf(
            "d" to field("d", a),
            "c" to field("c", null),
            "b" to field("b", a),
            "a" to field("a", null),
        )

        assertPlan(BuildExecutionPlan(input), listOf("c", "a"), mapOf(setOf(a) to listOf("d", "b")))
    }

    @Test
    fun `equivalent usage sets share a group regardless of occurrence order`() {
        val a = usage("A")
        val b = usage("B")
        val input = linkedMapOf("a" to field("a", a, b), "b" to field("b", b.copy(), a.copy()))

        assertPlan(BuildExecutionPlan(input), emptyList(), mapOf(setOf(a, b) to listOf("a", "b")))
    }

    @Test
    fun `only an equivalent parent set executes in the current group`() {
        val a = usage("A")
        val b = usage("B")
        val c = usage("C")
        val input = linkedMapOf(
            "a" to field("a", b, a),
            "b" to field("b", a),
            "c" to field("c", a, b, c),
            "d" to field("d", null),
        )

        val result = BuildExecutionPlan(input, setOf(a.copy(), b.copy()))

        assertPlan(result, listOf("a"), mapOf(setOf(a) to listOf("b"), setOf(a, b, c) to listOf("c"), emptySet<DeferUsage>() to listOf("d")))
    }

    @Test
    fun `planning does not mutate input or retain state between calls`() {
        val a = usage("A")
        val b = usage("B", a)
        val collected = field("a", b, a)
        val input = linkedMapOf("a" to collected, "b" to field("b", b))
        val parent = linkedSetOf(a)

        val withinA = BuildExecutionPlan(input, parent)
        val atRoot = BuildExecutionPlan(input)
        val withinAAgain = BuildExecutionPlan(input, parent)

        assertPlan(withinA, listOf("a"), mapOf(setOf(b) to listOf("b")))
        assertPlan(atRoot, emptyList(), mapOf(setOf(a) to listOf("a"), setOf(b) to listOf("b")))
        assertEquals(withinA, withinAAgain)
        input.keys.toList().shouldContainExactly("a", "b")
        parent.shouldContainExactly(a)
        collected.occurrences.map { it.deferUsage }.shouldContainExactly(b, a)
    }

    @Test
    fun `duplicate usages do not duplicate groups or discard occurrences`() {
        val a = usage("A")
        val collected = field("a", a, a, a.copy())

        val result = BuildExecutionPlan(mapOf("a" to collected))

        assertPlan(result, emptyList(), mapOf(setOf(a) to listOf("a")))
        val planned = result.newCollectedFieldsMaps.getValue(setOf(a)).getValue("a")
        assertSame(collected, planned)
        planned.occurrences.map { it.deferUsage }.shouldContainExactly(a, a, a)
    }

    @Test
    fun `fields with different redundant descendants share the normalized group`() {
        val a = usage("A")
        val b = usage("B", a)
        val c = usage("C", b)
        val input = linkedMapOf("a" to field("a", a), "b" to field("b", b, a), "c" to field("c", a, c))

        assertPlan(BuildExecutionPlan(input), emptyList(), mapOf(setOf(a) to listOf("a", "b", "c")))
    }

    @Test
    fun `unlabeled directives are not a single defer identity`() {
        val a = usage(null)
        val b = usage(null)

        assertPlan(
            BuildExecutionPlan(mapOf("a" to field("a", a), "b" to field("b", b), "c" to field("c", a, b))),
            emptyList(),
            mapOf(setOf(a) to listOf("a"), setOf(b) to listOf("b"), setOf(a, b) to listOf("c")),
        )
    }

    @Test
    fun `the same directive in different parent contexts has different ownership`() {
        val defer = mkDefer("shared")
        val a = DeferUsage(defer, usage("A"))
        val b = DeferUsage(defer, usage("B"))

        assertPlan(
            BuildExecutionPlan(mapOf("a" to field("a", a), "b" to field("b", b))),
            emptyList(),
            mapOf(setOf(a) to listOf("a"), setOf(b) to listOf("b")),
        )
    }

    private fun usage(
        label: String?,
        parent: DeferUsage? = null
    ) = DeferUsage(mkDefer(label), parent)

    private fun field(
        name: String,
        vararg usages: DeferUsage?
    ) = CollectedField(usages.map { FieldDetails(sourceFields.getValue(name), it) }, schema.schema)

    private fun assertPlan(
        plan: ExecutionPlan,
        immediate: List<String>,
        deferred: Map<Set<DeferUsage>, List<String>>,
    ) {
        plan.collectedFieldsMap.keys.toList().shouldContainExactly(immediate)
        plan.newCollectedFieldsMaps.keys.shouldContainExactlyInAnyOrder(deferred.keys)
        deferred.forEach { (usages, fields) -> plan.newCollectedFieldsMaps.getValue(usages).keys.toList().shouldContainExactly(fields) }
    }
}
