package viaduct.graphql.schema

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppliedDirectiveTest {
    @Test
    fun `AppliedDirective smoke test`() {
        val args: Map<String, ViaductSchema.Literal> = mapOf(
            "foo" to ViaductSchema.StringLiteral.of("bar"),
            "baz" to ViaductSchema.IntLiteral.of("1")
        )
        val mockDirective = MockDirective("name")
        val appliedDirective = ViaductSchema.AppliedDirective.of(mockDirective, args)
        assertEquals(appliedDirective.name, "name")
        assertEquals(appliedDirective.directive, mockDirective)
        assertEquals(appliedDirective.arguments.entries, args.entries)
    }

    /** Minimal mock directive for testing AppliedDirective */
    private class MockDirective(override val name: String) : ViaductSchema.Directive {
        override val containingSchema: ViaductSchema = ViaductSchema.Empty
        override val args: Collection<ViaductSchema.DirectiveArg> = emptyList()
        override val isRepeatable: Boolean = false
        override val allowedLocations: Set<ViaductSchema.Directive.Location> = emptySet()
        override val appliedDirectives: Collection<ViaductSchema.AppliedDirective<*>> = emptyList()
        override val sourceLocation: ViaductSchema.SourceLocation? = null

        override fun describe() = "MockDirective<$name>"
    }
}
