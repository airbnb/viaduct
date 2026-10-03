package viaduct.engine.runtime.execution

import graphql.language.Field
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.execution.constraints.Constraints

class GetFilteredDeferUsageSetTest {
    @Test
    fun `empty input returns no usages`() {
        GetFilteredDeferUsageSet(emptyList()).shouldBeEmpty()
    }

    @Test
    fun `a non-deferred field returns no usages`() {
        GetFilteredDeferUsageSet(fieldDetails(null)).shouldBeEmpty()
    }

    @Test
    fun `a deferred field retains its usage`() {
        val usage = DeferUsage(mkDefer("A"), null)

        GetFilteredDeferUsageSet(fieldDetails(usage)).shouldContainExactlyInAnyOrder(usage)
    }

    @Test
    fun `a non-deferred occurrence overrides usages on both sides`() {
        val a = DeferUsage(mkDefer("A"), null)
        val b = DeferUsage(mkDefer("B"), null)

        GetFilteredDeferUsageSet(fieldDetails(a, null, b)).shouldBeEmpty()
    }

    @Test
    fun `repeated occurrences of the same usage are deduplicated`() {
        val usage = DeferUsage(mkDefer("A"), null)

        GetFilteredDeferUsageSet(fieldDetails(usage, usage, usage.copy())).shouldContainExactlyInAnyOrder(usage)
    }

    @Test
    fun `separate unlabeled defers remain distinct`() {
        val a = DeferUsage(mkDefer(null), null)
        val b = DeferUsage(mkDefer(null), null)

        GetFilteredDeferUsageSet(fieldDetails(a, b)).shouldContainExactlyInAnyOrder(a, b)
    }

    @Test
    fun `a parent usage masks a child listed first`() {
        val parent = DeferUsage(mkDefer("parent"), null)
        val child = DeferUsage(mkDefer("child"), parent)

        GetFilteredDeferUsageSet(fieldDetails(child, parent)).shouldContainExactlyInAnyOrder(parent)
    }

    @Test
    fun `an ancestor masks a descendant even when its immediate parent is absent`() {
        val a = DeferUsage(mkDefer("A"), null)
        val b = DeferUsage(mkDefer("B"), a)
        val c = DeferUsage(mkDefer("C"), b)

        GetFilteredDeferUsageSet(fieldDetails(c, a)).shouldContainExactlyInAnyOrder(a)
    }

    @Test
    fun `a usage is retained when none of its ancestors select the field`() {
        val a = DeferUsage(mkDefer("A"), null)
        val b = DeferUsage(mkDefer("B"), a)
        val c = DeferUsage(mkDefer("C"), b)

        GetFilteredDeferUsageSet(fieldDetails(c)).shouldContainExactlyInAnyOrder(c)
    }

    @Test
    fun `sibling usages remain distinct when their parent does not select the field`() {
        val parent = DeferUsage(mkDefer("parent"), null)
        val a = DeferUsage(mkDefer("A"), parent)
        val b = DeferUsage(mkDefer("B"), parent)

        GetFilteredDeferUsageSet(fieldDetails(a, b)).shouldContainExactlyInAnyOrder(a, b)
    }

    @Test
    fun `the same directive in different parent contexts remains distinct`() {
        val defer = mkDefer("shared")
        val a = DeferUsage(defer, DeferUsage(mkDefer("A"), null))
        val b = DeferUsage(defer, DeferUsage(mkDefer("B"), null))

        GetFilteredDeferUsageSet(fieldDetails(a, b)).shouldContainExactlyInAnyOrder(a, b)
    }

    @Test
    fun `filtering preserves all original field occurrences and their usages`() {
        val parent = DeferUsage(mkDefer("parent"), null)
        val child = DeferUsage(mkDefer("child"), parent)
        val fields = fieldDetails(child, parent, child).toMutableList()
        val original = fields.toList()

        GetFilteredDeferUsageSet(fields).shouldContainExactlyInAnyOrder(parent)

        fields.shouldContainExactly(original)
        fields.map { it.deferUsage }.shouldContainExactly(child, parent, child)
    }

    /** Filtering cases adapted from graphql-js src/execution/__tests__/defer-test.ts. */
    @Nested
    inner class GraphQLJsTests {
        @Test
        fun `a non-deferred occurrence overrides a deferred occurrence listed first`() {
            val usage = DeferUsage(mkDefer("A"), null)

            GetFilteredDeferUsageSet(fieldDetails(usage, null)).shouldBeEmpty()
        }

        @Test
        fun `a non-deferred occurrence overrides a deferred occurrence listed last`() {
            val usage = DeferUsage(mkDefer("A"), null)

            GetFilteredDeferUsageSet(fieldDetails(null, usage)).shouldBeEmpty()
        }

        @Test
        fun `a parent usage masks its child`() {
            val parent = DeferUsage(mkDefer("parent"), null)
            val child = DeferUsage(mkDefer("child"), parent)

            GetFilteredDeferUsageSet(fieldDetails(parent, child)).shouldContainExactlyInAnyOrder(parent)
        }

        @Test
        fun `nested defers retain only the outermost usage in any occurrence order`() {
            val a = DeferUsage(mkDefer("A"), null)
            val b = DeferUsage(mkDefer("B"), a)
            val c = DeferUsage(mkDefer("C"), b)
            val orders = listOf(listOf(a, b, c), listOf(a, c, b), listOf(b, a, c), listOf(b, c, a), listOf(c, a, b), listOf(c, b, a))

            for (usages in orders) {
                GetFilteredDeferUsageSet(fieldDetails(*usages.toTypedArray())).shouldContainExactlyInAnyOrder(a)
            }
        }

        @Test
        fun `masking one branch preserves a usage from another branch at a different depth`() {
            val a = DeferUsage(mkDefer("A"), null)
            val b = DeferUsage(mkDefer("B"), a)
            val c = DeferUsage(mkDefer("C"), null)
            val d = DeferUsage(mkDefer("D"), c)

            GetFilteredDeferUsageSet(fieldDetails(b, d, a)).shouldContainExactlyInAnyOrder(a, d)
        }
    }

    private fun fieldDetails(vararg usages: DeferUsage?): List<FieldDetails> =
        usages.map { usage ->
            FieldDetails(
                field = QueryPlan.Field(
                    resultKey = "field",
                    constraints = Constraints.Unconstrained,
                    field = Field.newField("field").build(),
                    selectionSet = null,
                    childPlans = emptyList(),
                    fieldTypeChildPlans = FieldTypeChildPlans.empty,
                ),
                deferUsage = usage,
            )
        }
}
