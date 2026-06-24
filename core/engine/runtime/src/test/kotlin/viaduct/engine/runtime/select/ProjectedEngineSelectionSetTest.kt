package viaduct.engine.runtime.select

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.select.SelectionsParser

class ProjectedEngineSelectionSetTest {
    private val defaultSdl =
        """
            type Struct { int: Int }

            enum Bar { A }

            type Foo implements Node {
              id: ID!
              int: Int
              foo: Foo
              bar: Bar
              struct: Struct
            }

            union FooOrStruct = Foo | Struct
            union FooUnion = Foo

            type Baz implements Node { id: ID! }
        """

    private fun mk(
        typename: String,
        selections: String,
        sdl: String = defaultSdl,
        vars: Map<String, Any?> = mapOf(),
    ): EngineSelectionSetImpl =
        MockSchema.mk(sdl).let { schema ->
            EngineSelectionSetImpl.create(
                SelectionsParser.parse(typename, selections),
                vars,
                schema,
            )
        }

    @Test
    fun `ProjectedEngineSelectionSet -- containsField`() {
        val projected = mk("Node", "id ... on Foo { int bar }").selectionSetForType("Foo")

        assertTrue(projected.containsField("Foo", "id"))
        assertTrue(projected.containsField("Foo", "int"))
        assertTrue(projected.containsField("Foo", "bar"))
        assertFalse(projected.containsField("Foo", "foo"))

        assertTrue(projected.containsField("Node", "id"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- containsSelection`() {
        val projected = mk("Node", "id aliased:id ... on Foo { int }").selectionSetForType("Foo")
        assertTrue(projected.containsSelection("Foo", "id")) // field name lookup
        assertTrue(projected.containsSelection("Foo", "aliased")) // alias lookup
        assertTrue(projected.containsSelection("Foo", "int")) // concrete member field
        assertFalse(projected.containsSelection("Foo", "bar")) // absent field
        assertFalse(projected.containsSelection("Foo", "aliased2")) // alias is not the field name
    }

    @Test
    fun `ProjectedEngineSelectionSet -- resolveSelection`() {
        val projected = mk("Node", "id aliased:id ... on Foo { int }").selectionSetForType("Foo")
        assertEquals(
            EngineSelection("Node", "id", "id"),
            projected.resolveSelection("Foo", "id")
        )
        assertEquals(
            EngineSelection("Node", "id", "aliased"),
            projected.resolveSelection("Foo", "aliased")
        )
        assertEquals(
            EngineSelection("Foo", "int", "int"),
            projected.resolveSelection("Foo", "int")
        )
        assertThrows<IllegalArgumentException> {
            projected.resolveSelection("Foo", "unknown")
        }
    }

    @Test
    fun `ProjectedEngineSelectionSet -- fieldDirectivesOfSelection`() {
        val sdl = defaultSdl + "\ndirective @testDirective(flag: Boolean!) on FIELD"
        val projected = mk(
            "Node",
            "... on Foo { aliased: int @testDirective(flag: true) }",
            sdl
        ).selectionSetForType("Foo")

        val directives = projected.fieldDirectivesOfSelection("Foo", "aliased")!!

        assertTrue(directives.hasDirective("testDirective") { args -> args["flag"] == true })
        assertFalse(directives.hasDirective("missingDirective"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- selections`() {
        val projected = mk("Node", "id ... on Foo { int }").selectionSetForType("Foo")
        val selectedFields = projected.selections().map { it.fieldName }.toSet()
        assertEquals(setOf("id", "int"), selectedFields)
    }

    @Test
    fun `ProjectedEngineSelectionSet -- isEmpty`() {
        val projected = mk("Node", "__typename @skip(if:true)").selectionSetForType("Foo")
        assertTrue(projected.isEmpty())
    }

    @Test
    fun `ProjectedEngineSelectionSet -- traversableSelections`() {
        val projected = mk("Node", "id ... on Foo { foo { int } }").selectionSetForType("Foo")
        val traversable = projected.traversableSelections()
        assertEquals(listOf(EngineSelection("Foo", "foo", "foo")), traversable)
    }

    @Test
    fun `ProjectedEngineSelectionSet -- does not return selections for foreign type conditions`() {
        val projected = mk(
            "FooOrStruct",
            "__typename ... on Foo { id } ... on Struct { int }"
        ).selectionSetForType("Foo")
        assertFalse(projected.containsField("Struct", "int"))
        assertFalse(projected.containsSelection("Struct", "int"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- duplicate result keys from overlapping fragments`() {
        val projected = mk("Node", "id ... on Foo { id }").selectionSetForType("Foo")
        assertTrue(projected.containsSelection("Foo", "id"))
        assertEquals(EngineSelection("Node", "id", "id"), projected.resolveSelection("Foo", "id"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- consistency with EngineSelectionSetImpl`() {
        val impl = mk("Node", "id ... on Foo { int bar }")
        val projected = impl.selectionSetForType("Foo")
        for (field in listOf("id", "int", "bar", "foo", "__typename", "struct")) {
            assertEquals(
                impl.containsField("Foo", field),
                projected.containsField("Foo", field),
                "containsField(\"Foo\", \"$field\") mismatch"
            )
        }
        for (sel in listOf("id", "int", "bar", "foo", "__typename")) {
            assertEquals(
                impl.containsSelection("Foo", sel),
                projected.containsSelection("Foo", sel),
                "containsSelection(\"Foo\", \"$sel\") mismatch"
            )
        }
    }

    @Test
    fun `ProjectedEngineSelectionSet -- resolveSelection for duplicate result keys matches EngineSelectionSetImpl`() {
        val impl = mk("Node", "id ... on Foo { id }")
        val projected = impl.selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        assertEquals(impl.resolveSelection("Foo", "id"), projected.resolveSelection("Foo", "id"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- equality is symmetric with source impl`() {
        val projected = mk("Node", "id ... on Foo { int }").selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        val projectedImpl = projected as ProjectedEngineSelectionSet
        val sourceImpl = projectedImpl.sourceImpl
        assertEquals(sourceImpl, projectedImpl)
        assertEquals(projectedImpl, sourceImpl)
        assertEquals(sourceImpl.hashCode(), projected.hashCode())
    }

    @Test
    fun `ProjectedEngineSelectionSet -- equivalent projections compare equal`() {
        val impl = mk("Node", "id ... on Foo { int }")
        val projected1 = impl.selectionSetForType("Foo")
        val projected2 = impl.selectionSetForType("Foo")

        assertTrue(projected1 is ProjectedEngineSelectionSet)
        assertTrue(projected2 is ProjectedEngineSelectionSet)
        assertEquals(projected1, projected2)
        assertEquals(projected1.hashCode(), projected2.hashCode())
    }

    @Test
    fun `ProjectedEngineSelectionSet -- different concrete projections from same abstract source are not equal`() {
        val impl = mk("Node", "id ... on Foo { int }")
        val projectedFoo = impl.selectionSetForType("Foo")
        val projectedBaz = impl.selectionSetForType("Baz")

        assertTrue(projectedFoo is ProjectedEngineSelectionSet)
        assertTrue(projectedBaz is ProjectedEngineSelectionSet)

        val projectedFooImpl = projectedFoo as ProjectedEngineSelectionSet
        val projectedBazImpl = projectedBaz as ProjectedEngineSelectionSet

        assertNotEquals(projectedFooImpl, projectedBazImpl)
        assertNotEquals(projectedFooImpl, impl)
        assertNotEquals(projectedBazImpl, impl)
        assertNotEquals(projectedFooImpl.sourceImpl, projectedBazImpl.sourceImpl)
        assertNotEquals(projectedFooImpl, projectedBazImpl.sourceImpl)
        assertNotEquals(projectedBazImpl, projectedFooImpl.sourceImpl)
    }

    @Test
    fun `ProjectedEngineSelectionSet -- resolveSelection falls back to sourceImpl for non-concrete type`() {
        val projected = mk("Node", "id ... on Foo { int }").selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        assertEquals(EngineSelection("Node", "id", "id"), projected.resolveSelection("Node", "id"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- fieldDirectivesOfSelection falls back to sourceImpl for non-concrete type`() {
        val sdl = defaultSdl + "\ndirective @testDirective on FIELD"
        val projected = mk("Node", "id @testDirective ... on Foo { int }", sdl)
            .selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        val directives = projected.fieldDirectivesOfSelection("Node", "id")!!

        assertTrue(directives.hasDirective("testDirective"))
        assertFalse(directives.hasDirective("missingDirective"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- selectionSetForType throws for unknown type`() {
        val projected = mk("Node", "id").selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        assertThrows<IllegalArgumentException> {
            projected.selectionSetForType("TypeThatDoesNotExist")
        }
    }

    @Test
    fun `ProjectedEngineSelectionSet -- selectionSetForType throws for non-composite type`() {
        val projected = mk("Node", "id").selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        assertThrows<IllegalArgumentException> {
            projected.selectionSetForType("Bar")
        }
    }

    @Test
    fun `ProjectedEngineSelectionSet -- selectionSetForField delegates correctly`() {
        val projected = mk("Node", "... on Foo { foo { int } }").selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        val subSS = projected.selectionSetForField("Foo", "foo")
        assertTrue(subSS.containsField("Foo", "int"))
        assertFalse(subSS.containsField("Foo", "id"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- field merging`() {
        // Verify that selectionSetForField correctly merges subselections from duplicate field
        // occurrences. selectionsByResultKey (first-wins) is for existence/identity lookups only;
        // sub-selection merging is handled by sourceImpl.selectionSetForField which folds all
        // matching occurrences together via buildSubselections.
        val projected = mk(
            "Node",
            """
                ... on Foo {
                    struct { __typename }
                    struct { int }
                }
            """.trimIndent()
        ).selectionSetForType("Foo")
        val struct = projected.selectionSetForField("Foo", "struct")
        assertTrue(struct.containsField("Struct", "__typename"))
        assertTrue(struct.containsField("Struct", "int"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- selectionSetForSelection delegates for field name and alias`() {
        val projected = mk("Node", "... on Foo { a:foo { int } foo { id } }").selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        val aliasSubSS = projected.selectionSetForSelection("Foo", "a")
        assertTrue(aliasSubSS.containsField("Foo", "int"))
        assertFalse(aliasSubSS.containsField("Foo", "id"))

        val fieldSubSS = projected.selectionSetForSelection("Foo", "foo")
        assertTrue(fieldSubSS.containsField("Foo", "id"))
        assertFalse(fieldSubSS.containsField("Foo", "int"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- argumentsOfSelection delegates correctly`() {
        val sdl = """
            type Widget implements Node { id: ID!, total(limit: Int): Int }
        """.trimIndent()
        val projected = mk("Node", "... on Widget { total(limit: 10) }", sdl = sdl)
            .selectionSetForType("Widget")
        assertTrue(projected is ProjectedEngineSelectionSet)

        assertEquals(mapOf("limit" to 10), projected.argumentsOfSelection("Widget", "total"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- requestsType delegates correctly`() {
        val impl = mk("Node", "id ... on Foo { int }")
        val projected = impl.selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        assertEquals(impl.requestsType("Foo"), projected.requestsType("Foo"))
        assertEquals(impl.requestsType("Node"), projected.requestsType("Node"))
        assertEquals(impl.requestsType("Baz"), projected.requestsType("Baz"))
    }

    @Test
    fun `ProjectedEngineSelectionSet -- expanded consistency for resolveSelection selections and traversableSelections`() {
        val impl = mk("Node", "id ... on Foo { int bar foo { int } struct { int } }")
        val projected = impl.selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)
        val sourceImpl = (projected as ProjectedEngineSelectionSet).sourceImpl

        // resolveSelection must agree for all selected result keys
        for (sel in listOf("id", "int", "bar")) {
            assertEquals(
                sourceImpl.resolveSelection("Foo", sel),
                projected.resolveSelection("Foo", sel),
                "resolveSelection(\"Foo\", \"$sel\") mismatch"
            )
        }

        // selections() must return identical sets
        assertEquals(
            sourceImpl.selections().toSet(),
            projected.selections().toSet(),
            "selections() mismatch"
        )

        // traversableSelections() must return identical sets
        assertEquals(
            sourceImpl.traversableSelections().toSet(),
            projected.traversableSelections().toSet(),
            "traversableSelections() mismatch"
        )
    }

    @Test
    fun `ProjectedEngineSelectionSet -- isTransitivelyEmpty returns false with non-empty subselections`() {
        val projected = mk("Node", "... on Foo { foo { int } }").selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        assertFalse(projected.isTransitivelyEmpty())
    }

    @Test
    fun `ProjectedEngineSelectionSet addVariables -- preserves projection`() {
        // addVariables binds runtime values and re-evaluates @skip/@include directives.
        // The returned ESS must remain a ProjectedEngineSelectionSet so O(1) lookups are retained.
        val projected = mk("Node", "... on Foo { id int }").selectionSetForType("Foo")
        assertTrue(projected is ProjectedEngineSelectionSet)

        val withVars = projected.addVariables(mapOf("newVar" to "value"))

        // addVariables preserves the projection type
        assertTrue(withVars is ProjectedEngineSelectionSet)

        // O(1) lookups still work after the variable update
        assertTrue(withVars.containsSelection("Foo", "id"))
        assertTrue(withVars.containsField("Foo", "int"))
        assertFalse(withVars.containsSelection("Foo", "nonexistent"))
    }

    @Test
    fun `ProjectedEngineSelectionSet addVariables -- updates projection`() {
        val projected = mk(
            "Node",
            "... on Foo { id @skip(if:\$var) int }"
        ).selectionSetForType("Foo")

        assertTrue(projected is ProjectedEngineSelectionSet)

        val withVars = projected.addVariables(mapOf("var" to true))

        // addVariables preserves the projection type
        assertTrue(withVars is ProjectedEngineSelectionSet)

        // selections that depended on a variable value have been updated
        assertFalse(withVars.containsSelection("Foo", "id"))
        assertTrue(withVars.containsField("Foo", "int"))
    }
}
