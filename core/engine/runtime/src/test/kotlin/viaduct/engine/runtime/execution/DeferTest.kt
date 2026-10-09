package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.language.Directive
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeferTest {
    @Test
    fun `same directive and label`() {
        val directive = Directive.newDirective().name("defer").build()
        val first = Defer("same", directive)
        val second = Defer("same", directive)

        assertEquals(first, second)
        assertEquals(1, setOf(first, second).size)
    }

    @Test
    fun `distinct directives with the same label`() {
        val first = mkDefer("same")
        val second = mkDefer("same")

        assertNotEquals(first, second)
        assertEquals(2, setOf(first, second).size)
    }

    @Test
    fun `distinct unlabeled directives`() {
        val first = mkDefer(null)
        val second = mkDefer(null)

        assertNotEquals(first, second)
    }

    @Test
    fun `different labels on the same directive`() {
        val directive = Directive.newDirective().name("defer").build()

        assertNotEquals(Defer("first", directive), Defer("second", directive))
    }

    @Test
    fun `equal defer usages`() {
        val directive = Directive.newDirective().name("defer").build()
        val parentDirective = Directive.newDirective().name("defer").build()
        val first = DeferUsage(Defer("child", directive), DeferUsage(Defer("parent", parentDirective), null))
        val second = DeferUsage(Defer("child", directive), DeferUsage(Defer("parent", parentDirective), null))

        assertEquals(first, second)
    }

    @Test
    fun `defer usages distinguish parent contexts`() {
        val defer = mkDefer("child")
        val parent = DeferUsage(mkDefer("parent"), null)

        assertNotEquals(DeferUsage(defer, null), DeferUsage(defer, parent))
    }

    @Nested
    inner class WorkTests {
        @Test
        fun `adding empty work reuses the existing instance`() {
            val group = DeferDeliveryGroup(ResultPath.rootPath(), "details", null)
            val work = Work(setOf(group), listOf(task(group)))

            assertSame(work, work + Work.empty)
            assertSame(work, work + Work(emptySet(), emptyList()))
            assertSame(Work.empty, Work.empty + Work.empty)
        }

        @Test
        fun `combining work deduplicates groups by identity and retains every task`() {
            val first = DeferDeliveryGroup(ResultPath.rootPath(), "details", null)
            val second = DeferDeliveryGroup(ResultPath.rootPath(), "details", null)
            val firstTask = task(first)
            val secondTask = task(first, second)
            val left = Work(setOf(first), listOf(firstTask))
            val right = Work(setOf(first, second), listOf(secondTask))

            val combined = left + right

            combined.groups.shouldContainExactly(first, second)
            combined.tasks.shouldContainExactly(firstTask, secondTask)
            left.groups.shouldContainExactly(first)
            left.tasks.shouldContainExactly(firstTask)
            right.groups.shouldContainExactly(first, second)
        }

        @Test
        fun `tasks run only when invoked and return nested work`() =
            runTest {
                val parent = DeferDeliveryGroup(ResultPath.rootPath(), "parent", null)
                val child = DeferDeliveryGroup(ResultPath.rootPath(), "child", parent)
                val nestedWork = Work(setOf(child), listOf(task(child)))
                var ran = false
                val task = ExecutionGroupTask(setOf(parent), parent.path) {
                    ran = true
                    ExecutionGroupResult(mapOf("value" to 1), emptyList(), nestedWork)
                }
                val work = Work(setOf(parent), listOf(task))

                assertFalse(ran)
                val result = work.tasks.single().run()

                assertEquals(mapOf("value" to 1), result.data)
                assertSame(nestedWork, result.work)
            }

        @Test
        fun `collected fields aggregate work through nested lists`() {
            val first = DeferDeliveryGroup(ResultPath.rootPath().segment("items").segment(0).segment(0), "details", null)
            val second = DeferDeliveryGroup(ResultPath.rootPath().segment("items").segment(1).segment(0), "details", null)
            val firstTask = task(first)
            val secondTask = task(second)
            val firstObject = FieldCompletionResult.ObjectResult(mapOf("value" to 1), true, Work(setOf(first), listOf(firstTask)))
            val secondObject = FieldCompletionResult.ObjectResult(mapOf("value" to 2), true, Work(setOf(second), listOf(secondTask)))
            val firstList = FieldCompletionResult.ListResult(listOf(firstObject.value), listOf(firstObject), true)
            val secondList = FieldCompletionResult.ListResult(listOf(secondObject.value, null), listOf(secondObject, FieldCompletionResult.NullResult(true)), true)
            val items = FieldCompletionResult.ListResult(listOf(firstList.value, secondList.value), listOf(firstList, secondList), true)

            val result = ExecutionPlanResult.fromFieldResults(
                linkedMapOf("scalar" to FieldCompletionResult.ScalarResult(3, true), "items" to items),
            )

            result.data.keys.toList().shouldContainExactly("scalar", "items")
            assertEquals(mapOf("scalar" to 3, "items" to listOf(listOf(mapOf("value" to 1)), listOf(mapOf("value" to 2), null))), result.data)
            result.work.groups.shouldContainExactly(first, second)
            result.work.tasks.shouldContainExactly(firstTask, secondTask)
        }

        @Test
        fun `null field results retain work from surviving siblings`() {
            val group = DeferDeliveryGroup(ResultPath.rootPath().segment("sibling"), "details", null)
            val siblingWork = Work(setOf(group), listOf(task(group)))

            val result = ExecutionPlanResult.fromFieldResults(
                linkedMapOf(
                    "failed" to FieldCompletionResult.NullResult(true),
                    "sibling" to FieldCompletionResult.ObjectResult(mapOf("value" to 1), true, siblingWork),
                ),
            )

            assertEquals(mapOf("failed" to null, "sibling" to mapOf("value" to 1)), result.data)
            assertEquals(siblingWork, result.work)
        }

        @Test
        fun `object result factory carries supplied work`() =
            runTest {
                val parameters = mkObjectCompletionParameters(
                    schemaSDL = "extend type Query { obj: Obj } type Obj { child: Child } type Child { value: Int }",
                    coordinate = "Query" to "obj",
                    query = "{ obj { child { value } } }",
                )
                val group = DeferDeliveryGroup(parameters.path.segment("child"), "details", null)
                val work = Work(setOf(group), listOf(task(group)))

                val result = FieldCompletionResult.obj(mapOf("child" to mapOf("value" to 1)), parameters, work)

                assertEquals(mapOf("child" to mapOf("value" to 1)), result.value)
                assertSame(work, result.work)
            }

        @Test
        fun `failed execution group results require errors`() {
            assertThrows<IllegalArgumentException> {
                ExecutionGroupResult(null, emptyList(), Work.empty)
            }
        }

        private fun task(vararg groups: DeferDeliveryGroup): ExecutionGroupTask =
            ExecutionGroupTask(groups.toSet(), groups.first().path) {
                ExecutionGroupResult(emptyMap(), emptyList(), Work.empty)
            }
    }
}
