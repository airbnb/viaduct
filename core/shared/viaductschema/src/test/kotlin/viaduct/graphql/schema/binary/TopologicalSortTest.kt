package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema

/**
 * Unit tests for [topologicalSortDirectives].
 *
 * These tests verify the topological sort algorithm for directives, covering:
 * - Empty input
 * - Single directive
 * - Multiple independent directives (alphabetical ordering)
 * - Linear dependency chains
 * - Reverse alphabetical dependency chains
 * - Diamond dependencies
 * - Cycle detection
 * - Deterministic output
 *
 * All tests use mock directives to avoid SDL parsing complexity (built-in directives,
 * applied directives on arguments not being preserved, etc.).
 */
class TopologicalSortTest {
    @Test
    fun `empty list returns empty list`() {
        val result = topologicalSortDirectives(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single directive with no dependencies`() {
        val foo = mockDirective("foo")
        val result = topologicalSortDirectives(listOf(foo))
        assertEquals(listOf("foo"), result.map { it.name })
    }

    @Test
    fun `multiple directives with no dependencies sorted alphabetically`() {
        val charlie = mockDirective("charlie")
        val alpha = mockDirective("alpha")
        val bravo = mockDirective("bravo")
        val result = topologicalSortDirectives(listOf(charlie, alpha, bravo))
        assertEquals(listOf("alpha", "bravo", "charlie"), result.map { it.name })
    }

    @Test
    fun `linear dependency chain - dependencies come first`() {
        // @zoo depends on @bar, @bar depends on @aaa
        // Despite alphabetical order being aaa, bar, zoo,
        // the dependency order is: aaa (no deps), bar (depends on aaa), zoo (depends on bar)
        val aaa = mockDirective("aaa")
        val bar = mockDirective("bar", "aaa")
        val zoo = mockDirective("zoo", "bar")
        val result = topologicalSortDirectives(listOf(zoo, bar, aaa)) // Pass in arbitrary order
        assertEquals(listOf("aaa", "bar", "zoo"), result.map { it.name })
    }

    @Test
    fun `reverse alphabetical dependency chain`() {
        // @aaa depends on @bbb, @bbb depends on @ccc
        // Alphabetically would be aaa, bbb, ccc but dependency order is ccc, bbb, aaa
        val aaa = mockDirective("aaa", "bbb")
        val bbb = mockDirective("bbb", "ccc")
        val ccc = mockDirective("ccc")
        val result = topologicalSortDirectives(listOf(aaa, bbb, ccc))
        assertEquals(listOf("ccc", "bbb", "aaa"), result.map { it.name })
    }

    @Test
    fun `diamond dependency - shared dependency comes first`() {
        // @top depends on @left and @right, both depend on @bottom
        val bottom = mockDirective("bottom")
        val left = mockDirective("left", "bottom")
        val right = mockDirective("right", "bottom")
        val top = mockDirective("top", "left", "right")
        val result = topologicalSortDirectives(listOf(top, right, left, bottom))
        val names = result.map { it.name }

        // bottom must come first
        assertEquals(0, names.indexOf("bottom"))
        // left and right must come before top (order between them is alphabetical)
        assertTrue(names.indexOf("left") < names.indexOf("top"))
        assertTrue(names.indexOf("right") < names.indexOf("top"))
        // top must come last
        assertEquals(3, names.indexOf("top"))
    }

    @Test
    fun `direct self-reference cycle throws exception`() {
        val selfRef = mockDirective("selfRef", "selfRef")
        val exception = assertThrows<IllegalArgumentException> {
            topologicalSortDirectives(listOf(selfRef))
        }
        assertTrue(exception.message!!.contains("Circular directive dependency"))
        assertTrue(exception.message!!.contains("@selfRef"))
    }

    @Test
    fun `indirect cycle throws exception with cycle path`() {
        // aaa -> bbb -> ccc -> aaa
        val aaa = mockDirective("aaa", "bbb")
        val bbb = mockDirective("bbb", "ccc")
        val ccc = mockDirective("ccc", "aaa")
        val exception = assertThrows<IllegalArgumentException> {
            topologicalSortDirectives(listOf(aaa, bbb, ccc))
        }
        assertTrue(exception.message!!.contains("Circular directive dependency"))
        // Should show the cycle path
        assertTrue(exception.message!!.contains("@aaa"))
        assertTrue(exception.message!!.contains("@bbb"))
        assertTrue(exception.message!!.contains("@ccc"))
    }

    @Test
    fun `multiple dependencies`() {
        // @multi depends on both @dep1 and @dep2
        val dep1 = mockDirective("dep1")
        val dep2 = mockDirective("dep2")
        val multi = mockDirective("multi", "dep1", "dep2")
        val result = topologicalSortDirectives(listOf(multi, dep1, dep2))
        val names = result.map { it.name }

        // dep1 and dep2 must come before multi
        assertTrue(names.indexOf("dep1") < names.indexOf("multi"))
        assertTrue(names.indexOf("dep2") < names.indexOf("multi"))
    }

    @Test
    fun `deterministic output for same input`() {
        val z = mockDirective("z")
        val a = mockDirective("a")
        val m = mockDirective("m")
        // Run multiple times to verify determinism
        val results = (1..5).map {
            topologicalSortDirectives(listOf(z, a, m)).map { it.name }
        }
        results.forEach { result ->
            assertEquals(listOf("a", "m", "z"), result)
        }
    }

    @Test
    fun `unknown directive reference throws exception`() {
        // @foo references @unknown which is not in the collection
        // This should fail because all referenced directives must be in the collection
        val foo = mockDirective("foo", "unknown")
        val exception = assertThrows<IllegalArgumentException> {
            topologicalSortDirectives(listOf(foo))
        }
        assertTrue(exception.message!!.contains("unknown directive"))
        assertTrue(exception.message!!.contains("@foo"))
        assertTrue(exception.message!!.contains("@unknown"))
    }

    @Test
    fun `complex multi-level dependencies`() {
        // Level 0: no dependencies
        val l0a = mockDirective("l0a")
        val l0b = mockDirective("l0b")
        // Level 1: depends on level 0
        val l1a = mockDirective("l1a", "l0a", "l0b")
        val l1b = mockDirective("l1b", "l0b")
        // Level 2: depends on level 1
        val l2 = mockDirective("l2", "l1a", "l1b")

        val result = topologicalSortDirectives(listOf(l2, l1b, l0b, l1a, l0a))
        val names = result.map { it.name }

        // Level 0 must come before level 1, level 1 before level 2
        assertTrue(names.indexOf("l0a") < names.indexOf("l1a"))
        assertTrue(names.indexOf("l0b") < names.indexOf("l1a"))
        assertTrue(names.indexOf("l0b") < names.indexOf("l1b"))
        assertTrue(names.indexOf("l1a") < names.indexOf("l2"))
        assertTrue(names.indexOf("l1b") < names.indexOf("l2"))
    }

    // Mock implementations for testing
    // These implement only what topologicalSortDirectives needs:
    // - directive.name
    // - directive.args -> arg.appliedDirectives -> appliedDirective.name

    private fun mockDirective(
        name: String,
        vararg dependsOn: String
    ): ViaductSchema.Directive {
        // Create simple reference directives for the dependencies.
        // These just need to have the correct name for the topological sort to work.
        val appliedDirs = dependsOn.map { depName ->
            val refDirective = MockDirective(depName, emptyList())
            ViaductSchema.AppliedDirective.of(refDirective, emptyMap())
        }
        val args: Collection<ViaductSchema.DirectiveArg> = if (appliedDirs.isNotEmpty()) {
            listOf(MockDirectiveArg("arg", appliedDirs))
        } else {
            emptyList()
        }
        return MockDirective(name, args)
    }

    private class MockDirective(
        override val name: String,
        override val args: Collection<ViaductSchema.DirectiveArg>
    ) : ViaductSchema.Directive {
        override val containingSchema: ViaductSchema = ViaductSchema.Empty
        override val isRepeatable: Boolean = false
        override val allowedLocations: Set<ViaductSchema.Directive.Location> = emptySet()
        override val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>> = emptyList()
        override val sourceLocation: ViaductSchema.SourceLocation? = null

        override fun describe() = "MockDirective<$name>"
    }

    private class MockDirectiveArg(
        override val name: String,
        override val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>>
    ) : ViaductSchema.DirectiveArg {
        override val containingDef: ViaductSchema.Directive
            get() = throw UnsupportedOperationException("Not needed for topological sort tests")
        override val type: ViaductSchema.TypeExpr<ViaductSchema.TypeDef>
            get() = throw UnsupportedOperationException("Not needed for topological sort tests")
        override val hasDefault: Boolean = false
        override val defaultValue: ViaductSchema.Literal
            get() = throw NoSuchElementException("Not needed for topological sort tests")
        override val sourceLocation: ViaductSchema.SourceLocation? = null

        override fun describe() = "MockDirectiveArg<$name>"
    }
}
