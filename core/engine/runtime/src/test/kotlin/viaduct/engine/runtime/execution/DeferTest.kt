package viaduct.engine.runtime.execution

import graphql.language.Directive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

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
}
