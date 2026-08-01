package viaduct.engine.runtime.select

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.select.SelectionsParser

class CoversTest {
    @Test
    fun `covered -- nested subset`() {
        val selectionSet = selectionSets(
            """
            type Test {
              foo: Foo
              bar: String
            }
            type Foo { a: String b: String }
            """.trimIndent()
        )

        assertCovers(
            selectionSet("Test", "foo { a b } bar"),
            selectionSet("Test", "foo { a }"),
        )
    }

    @Test
    fun `uncovered -- nested strict subset`() {
        val selectionSet = selectionSets(
            """
            type Test { foo: Foo }
            type Foo { a: String b: String }
            """.trimIndent()
        )

        assertDoesNotCover(
            selectionSet("Test", "foo { a }"),
            selectionSet("Test", "foo { a b }"),
        )
    }

    @Test
    fun `covered -- deeply nested subset`() {
        val selectionSet = selectionSets(
            """
            type Test { a:A }
            type A { b:B }
            type B { c:C }
            type C { x:Int, y:Int }
            """.trimIndent()
        )

        assertCovers(
            selectionSet("Test", "a { b { c { x y } } }"),
            selectionSet("Test", "a { b { c { x } } }"),
        )
    }

    @Test
    fun `uncovered -- deeply nested missing field`() {
        val selectionSet = selectionSets(
            """
            type Test { a:A }
            type A { b:B }
            type B { c:C }
            type C { x:Int, y:Int, z:Int }
            """.trimIndent()
        )

        assertDoesNotCover(
            selectionSet("Test", "a { b { c { x y } } }"),
            selectionSet("Test", "a { b { c { z } } }"),
        )
    }

    @Test
    fun `uncovered -- nested disjoint set`() {
        val selectionSet = selectionSets(
            """
            type Test { foo: Foo }
            type Foo { a: String b: String }
            """.trimIndent()
        )

        assertDoesNotCover(
            selectionSet("Test", "foo { a }"),
            selectionSet("Test", "foo { b }"),
        )
    }

    @Test
    fun `covered -- inline fragment spreads`() {
        val selectionSet = selectionSets(
            """
            interface HasFooA { foo: Foo }
            interface HasFooB { foo: Foo }
            type Test implements HasFooA & HasFooB { foo: Foo }
            type Foo { a: String b: String }
            """.trimIndent()
        )

        assertCovers(
            selectionSet("Test", "... on HasFooA { foo { a } } ... on HasFooB { foo { b } }"),
            selectionSet("Test", "foo { a b }"),
        )
    }

    @Test
    fun `uncovered -- different arguments`() {
        val selectionSet = selectionSets(
            """
            type Test { fooWithFactor(factor: Int): Foo }
            type Foo { a: String }
            """.trimIndent()
        )

        assertDoesNotCover(
            selectionSet("Test", "fooWithFactor(factor: 1) { a }"),
            selectionSet("Test", "fooWithFactor(factor: 2) { a }"),
        )
    }

    @Test
    fun `covered -- projected argument defaults`() {
        val selectionSet = selectionSets(
            """
            interface HasFoo { foo: Foo }
            type Test implements HasFoo { foo(factor: Int = 2): Foo }
            type Foo { a: String }
            """.trimIndent()
        )

        assertCovers(
            selectionSet("HasFoo", "foo { a }"),
            selectionSet("Test", "foo(factor: 2) { a }"),
        )
    }

    @Test
    fun `covered -- projected covariant field type`() {
        val selectionSet = selectionSets(
            """
            interface Parent { child: Child }
            interface Child { value: String }
            type ParentImpl implements Parent { child: ChildA }
            type ChildA implements Child { value: String }
            """.trimIndent()
        )

        assertCovers(
            selectionSet("ParentImpl", "child { value }"),
            selectionSet("Parent", "child { value }"),
        )
    }

    @Test
    fun `uncovered -- different response keys`() {
        val selectionSet = selectionSets(
            """
            type Test { foo: Foo }
            type Foo { a: String }
            """.trimIndent()
        )

        assertDoesNotCover(
            selectionSet("Test", "aliased: foo { a }"),
            selectionSet("Test", "foo { a }"),
        )
    }

    @Test
    fun `covered -- projected`() {
        val selectionSet = selectionSets(
            """
            interface Entity { id: ID! }
            type Test implements Entity { id: ID! foo: Foo }
            type Foo { a: String }
            """.trimIndent()
        )
        val projected = selectionSet("Entity", "id ... on Test { foo { a } }")
            .selectionSetForType("Test")

        assertCovers(projected, selectionSet("Test", "foo { a }"))
    }

    @Test
    fun `directives are evaluated before comparison`() {
        val selectionSet = selectionSets(
            """
            type Test { foo: Foo }
            type Foo { a: String b: String }
            """.trimIndent()
        )

        assertCovers(
            selectionSet("Test", "foo { a }"),
            selectionSet("Test", "foo { a b @skip(if: true) }"),
        )
    }

    @Test
    fun `covered -- named fragment spreads`() {
        val selectionSet = selectionSets(
            """
            type Test { foo: Foo }
            type Foo { a: String b: String }
            """.trimIndent()
        )

        assertCovers(
            selectionSet(
                "Test",
                """
                fragment FooFields on Foo { a b }
                fragment Main on Test { foo { ...FooFields } }
                """.trimIndent()
            ),
            selectionSet("Test", "foo { a }"),
        )
    }

    @Test
    fun `covered -- sibling interface fragment applies to implementing concrete type`() {
        val selectionSet = selectionSets(
            """
            interface Entity { id: ID! }
            interface HasFoo { foo: Foo }
            type Test implements Entity & HasFoo { id: ID! foo: Foo }
            type Foo { a: String }
            """.trimIndent()
        )

        assertCovers(
            selectionSet("Entity", "... on HasFoo { foo { a } }"),
            selectionSet("Test", "foo { a }"),
        )
    }

    @Test
    fun `covered -- concrete fragment applies after narrowing interface selection set`() {
        val selectionSet = selectionSets(
            """
            interface Entity { id: ID! }
            type Test implements Entity { id: ID! bar: String }
            """.trimIndent()
        )

        assertCovers(
            selectionSet("Entity", "... on Test { bar }"),
            selectionSet("Test", "bar"),
        )
    }

    @Test
    fun `covered -- widening type conditions`() {
        val selectionSet = selectionSets(
            """
            interface Entity { id: ID! }
            type Test implements Entity { id: ID! }
            """.trimIndent()
        )

        assertCovers(
            selectionSet("Test", "... on Entity { id }"),
            selectionSet("Test", "id"),
        )
    }

    @Test
    fun `covered -- different requested concrete types`() {
        val selectionSet = selectionSets(
            """
            type Test { item: Item }
            interface Item { value: String }
            type ItemA implements Item { value: String }
            type ItemB implements Item { value: String }
            """.trimIndent()
        )
        val covered = selectionSet("Test", "item { value }")
        val required = selectionSet("Test", "item { ... on ItemA { value } }")

        assertCovers(covered, required)
    }

    private fun assertCovers(
        covering: EngineSelectionSet,
        required: EngineSelectionSet,
    ) {
        assertTrue(
            covering.covers(required),
            "Expected ${covering.description()} to cover ${required.description()}",
        )
    }

    private fun assertDoesNotCover(
        covering: EngineSelectionSet,
        required: EngineSelectionSet,
    ) {
        assertFalse(
            covering.covers(required),
            "Expected ${covering.description()} not to cover ${required.description()}",
        )
    }

    private fun EngineSelectionSet.description(): String = "$type { ${printAsFieldSet()} }"

    private fun selectionSets(sdl: String): (String, String) -> EngineSelectionSet {
        val schema = MockSchema.mk(sdl)
        return { type, selections ->
            EngineSelectionSetImpl.create(
                SelectionsParser.parse(type, selections),
                emptyMap(),
                schema,
            )
        }
    }
}
