package viaduct.tenant.codegen.bytecode.exercise

import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import viaduct.invariants.FailureCollector

class ExerciserUtilsTest {
    private class Outer {
        object Inner {
            val x = 1
        }
    }

    private val shouldNotRun = { _: Any -> fail("fn was run unexpectedly") }

    @Test
    fun `withNestedClasses`() {
        // happy path
        FailureCollector().also { check ->
            var ran = false
            check.withNestedClass(Outer::class, "Inner", "LABEL") {
                assertEquals(Outer.Inner::class, it)
                ran = true
            }
            check.assertEmpty("\n")
            assertTrue(ran)
        }

        // error path
        FailureCollector().also { check ->
            check.withNestedClass(Outer::class, "Undefined", "LABEL", shouldNotRun)
            check.assertContainsLabels("LABEL")
        }
    }

    @Test
    fun `withObjectInstance`() {
        // happy path
        FailureCollector().also { check ->
            var ran = false
            check.withObjectInstance(Outer.Inner::class, "LABEL") {
                ran = true
                assertEquals(Outer.Inner.x, it.x)
            }

            check.assertEmpty("\n")
            assertTrue(ran)
        }

        // error path
        FailureCollector().also { check ->
            check.withObjectInstance(Outer::class, "LABEL", shouldNotRun)
            check.assertContainsLabels("LABEL")
        }
    }

    @Test
    fun `withProperty`() {
        // happy path
        FailureCollector().also { check ->
            var ran = false
            check.withProperty<Int>(Outer.Inner, "x", "LABEL") {
                ran = true
                assertEquals(1, it)
            }
            check.assertEmpty("\n")
            assertTrue(ran)
        }

        // no prop
        FailureCollector().also { check ->
            check.withProperty<Int>(Outer.Inner, "missing", "LABEL", shouldNotRun)
            check.assertContainsLabels("LABEL")
        }

        // prop has wrong type
        FailureCollector().also { check ->
            check.withProperty<String>(Outer.Inner, "x", "LABEL", shouldNotRun)
            check.assertContainsLabels("LABEL")
        }
    }
}
