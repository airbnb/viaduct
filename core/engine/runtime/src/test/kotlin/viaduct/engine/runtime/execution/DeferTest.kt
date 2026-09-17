package viaduct.engine.runtime.execution

import graphql.language.Directive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class DeferTest {
    @Test
    fun `defer usages distinguish AST occurrences and parent contexts`() {
        val directive = Directive.newDirective().name("defer").build()
        val defer = Defer("same", directive)
        val sameDirective = Defer("same", directive)
        val otherDirective = Defer("same", Directive.newDirective().name("defer").build())

        assertEquals(defer, sameDirective)
        assertEquals(setOf(defer), setOf(defer, sameDirective))
        assertNotEquals(defer, otherDirective)
        assertNotEquals(Defer(null), Defer(null))
        val parent = DeferUsage(Defer("parent"), null)
        assertEquals(DeferUsage(defer, parent), DeferUsage(sameDirective, parent))
        assertNotEquals(DeferUsage(defer, null), DeferUsage(defer, parent))
        assertNotEquals(DeferUsage(defer, parent), DeferUsage(defer, DeferUsage(Defer("parent"), null)))
    }
}
