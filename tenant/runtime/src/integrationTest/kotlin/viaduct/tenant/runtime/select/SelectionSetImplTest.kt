package viaduct.tenant.runtime.select

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.reflect.Type
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.mocks.createRawSelectionSet
import viaduct.engine.api.select.SelectionsParser

@ExperimentalCoroutinesApi
class SelectionSetImplTest {
    private fun <T : CompositeOutput> mk(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?> = mapOf()
    ): SelectionSetImpl<T> =
        SelectionSetImpl(
            type,
            createRawSelectionSet(
                SelectionsParser.parse(type.name, selections),
                SelectTestFeatureAppTest.schema,
                variables,
            )
        )

    @Test
    fun `containsField -- object own fields`() {
        val ss = mk(Foo.Reflection, "id")
        assertTrue(ss.contains(Foo.Reflection.Fields.id))
        assertFalse(ss.contains(Foo.Reflection.Fields.fooSelf))
    }

    @Test
    fun `containsField -- interface own fields`() {
        val ss = mk(Node.Reflection, "id")
        assertTrue(ss.contains(Node.Reflection.Fields.id))
        assertFalse(ss.contains(Node.Reflection.Fields.nodeSelf))
    }

    @Test
    fun `containsField -- interface impl fields`() {
        val ss = mk(Node.Reflection, "id, ... on Foo { nodeSelf }")

        /**
         * In deciding if the field being tested is contained in the current SelectionSet,
         * this method will consider any type-condition-less selections on the parent type
         * to be included in the selections of any child types, but selections on child types
         * will not be considered to be included on parent types.
         */
        assertTrue(ss.contains(Node.Reflection.Fields.id))
        assertFalse(ss.contains(Node.Reflection.Fields.nodeSelf))

        assertTrue(ss.contains(Foo.Reflection.Fields.id))
        assertTrue(ss.contains(Foo.Reflection.Fields.nodeSelf))
    }

    @Test
    fun `requestsType -- object`() {
        // an empty selection set requests its own type
        var ss: SelectionSetImpl<Foo> = mk(Foo.Reflection, "__typename @skip(if:true)")
        assertTrue(ss.requestsType(Foo.Reflection))

        // a non-empty selection set contains the type that it is selected on
        ss = mk(Foo.Reflection, "__typename")
        assertTrue(ss.requestsType(Foo.Reflection))

        // object selections can be wrapped in inline fragments without a type condition
        ss = mk(Foo.Reflection, "... { id }")
        assertTrue(ss.requestsType(Foo.Reflection))

        // object selections can be wrapped in inline fragments with a type condition
        ss = mk(Foo.Reflection, "... on Foo { id }")
        assertTrue(ss.requestsType(Foo.Reflection))

        // object selections that contain a wider inline fragment still request the object type
        ss = mk(Foo.Reflection, "... on Node { id }")
        assertTrue(ss.requestsType(Foo.Reflection))

        // skipped inline fragments are sufficient to make a type requested
        ss = mk(Foo.Reflection, "... on Foo @skip(if:true) { id }")
        assertTrue(ss.requestsType(Foo.Reflection))

        // empty inline fragments are sufficient to make a type requested
        ss = mk(Foo.Reflection, "... on Foo { id @skip(if:true) }")
        assertTrue(ss.requestsType(Foo.Reflection))

        // fragment spreads are traversed
        ss =
            mk(
                Foo.Reflection,
                """
                fragment Frag on Foo { __typename }
                fragment Main on Foo { ... Frag }
                """.trimIndent()
            )
        assertTrue(ss.requestsType(Foo.Reflection))
    }

    @Test
    fun `requestsType -- union`() {
        // empty selection sets request the parent type but not member types
        var ss: SelectionSetImpl<FooOrBar> = mk(FooOrBar.Reflection, "__typename @skip(if:true)")
        assertTrue(ss.requestsType(FooOrBar.Reflection))
        assertFalse(ss.requestsType(Foo.Reflection))
        assertFalse(ss.requestsType(Bar.Reflection))

        // simple inline fragment with no inherited fields
        // Selected types and the parent type are considered requested
        ss = mk(FooOrBar.Reflection, "... on Foo { id }")
        assertTrue(ss.requestsType(FooOrBar.Reflection))
        assertTrue(ss.requestsType(Foo.Reflection))
        assertFalse(ss.requestsType(Bar.Reflection))

        // selections on the parent type are not sufficient to make a subtype requested
        ss = mk(FooOrBar.Reflection, "__typename, ... on Foo { __typename }")
        assertTrue(ss.requestsType(FooOrBar.Reflection))
        assertTrue(ss.requestsType(Foo.Reflection))
        assertFalse(ss.requestsType(Bar.Reflection))

        // empty fragment spreads request the type of the fragment
        ss =
            mk(
                FooOrBar.Reflection,
                """
                fragment Frag on Foo { __typename @skip(if:true) }
                fragment Main on FooOrBar { ... Frag }
                """.trimIndent()
            )
        assertTrue(ss.requestsType(Foo.Reflection))
    }

    @Test
    fun `requestsType -- interface`() {
        // empty selection sets request the interface type but not its impls
        var ss: SelectionSetImpl<Node> = mk(Node.Reflection, "__typename @skip(if:true)")
        assertTrue(ss.requestsType(Node.Reflection))
        assertFalse(ss.requestsType(Foo.Reflection))

        // simple inline fragment with no inherited fields
        // selected types and the parent type are considered included
        ss = mk(Node.Reflection, "... on Foo { __typename }")
        assertTrue(ss.requestsType(Node.Reflection))
        assertTrue(ss.requestsType(Foo.Reflection))

        // selections on a parent are not sufficient to make a subtype requested
        ss = mk(Node.Reflection, "id")
        assertTrue(ss.requestsType(Node.Reflection))
        assertFalse(ss.requestsType(Foo.Reflection))

        // empty fragment spreads request the type of the fragment
        ss =
            mk(
                Node.Reflection,
                """
                fragment Frag on Foo { __typename @skip(if:true) }
                fragment Main on Node { ... Frag }
                """.trimIndent()
            )
        assertTrue(ss.requestsType(Foo.Reflection))
    }

    @Test
    fun `selectionSetFor field -- object`() {
        // subselecting an unselected field returns empty
        var ss: SelectionSetImpl<Foo> = mk(Foo.Reflection, "__typename @skip(if:true)")
        assertTrue(ss.selectionSetFor(Foo.Reflection.Fields.fooSelf).isEmpty())

        // subselecting a populated selection set contains selected fields
        ss = mk(Foo.Reflection, "fooSelf { id }")
        assertTrue(ss.selectionSetFor(Foo.Reflection.Fields.fooSelf).contains(Foo.Reflection.Fields.id))
    }

    @Test
    fun `selectionSetFor field -- interface`() {
        // subselecting an unselected field returns empty
        var ss: SelectionSetImpl<Node> = mk(Node.Reflection, "__typename @skip(if:true)")
        assertTrue(ss.selectionSetFor(Node.Reflection.Fields.nodeSelf).isEmpty())

        // subselecting an interface field contains selected fields
        ss = mk(Node.Reflection, "nodeSelf { id }")
        assertTrue(ss.selectionSetFor(Node.Reflection.Fields.nodeSelf).contains(Node.Reflection.Fields.id))

        // subselecting an impl field traverses type conditions
        ss = mk(Node.Reflection, "... on Foo { nodeSelf { id } }")
        assertTrue(ss.selectionSetFor(Foo.Reflection.Fields.nodeSelf).contains(Node.Reflection.Fields.id))
        assertTrue(ss.selectionSetFor(Node.Reflection.Fields.nodeSelf).isEmpty())

        // subselecting an impl field will merge interface and impl selections
        ss = mk(Node.Reflection, "nodeSelf { nodeSelf { id } } ... on Foo { nodeSelf { id } }")
        ss.selectionSetFor(Foo.Reflection.Fields.nodeSelf).let {
            assertTrue(it.contains(Node.Reflection.Fields.nodeSelf)) // interface selection
            assertTrue(it.contains(Node.Reflection.Fields.id)) // impl selection
        }
        ss.selectionSetFor(Node.Reflection.Fields.nodeSelf).let {
            assertTrue(it.contains(Node.Reflection.Fields.nodeSelf)) // interface selection
            assertFalse(it.contains(Node.Reflection.Fields.id)) // impl selection is excluded because it is guarded by a type condition
        }
    }

    @Test
    fun `selectionSetFor field -- union`() {
        // empty
        var ss: SelectionSetImpl<FooOrBar> = mk(FooOrBar.Reflection, "__typename @skip(if:true)")
        assertTrue(ss.selectionSetFor(Foo.Reflection.Fields.fooSelf).isEmpty())

        // empty fragment
        ss = mk(FooOrBar.Reflection, "... on Foo { fooSelf { id @skip(if:true) } }")
        assertTrue(ss.selectionSetFor(Foo.Reflection.Fields.fooSelf).isEmpty())

        // non-empty fragment
        ss = mk(FooOrBar.Reflection, "... on Foo { fooSelf { id } }")
        assertTrue(ss.selectionSetFor(Foo.Reflection.Fields.fooSelf).contains(Foo.Reflection.Fields.id))
    }

    @Test
    fun `selectionSetFor type -- object`() {
        // self projections return same selection set
        val ss: SelectionSetImpl<Foo> = mk(Foo.Reflection, "__typename @skip(if:true)")
        assertEquals(ss, ss.selectionSetFor(Foo.Reflection))
    }

    @Test
    fun `selectionSetFor type -- interface`() {
        // self projections return same selection set
        var ss: SelectionSetImpl<Node> = mk(Node.Reflection, "__typename @skip(if:true)")
        assertEquals(ss, ss.selectionSetFor(Node.Reflection))

        // an implementation can be projected even without type conditions
        ss = mk(Node.Reflection, "id")
        assertTrue(ss.selectionSetFor(Foo.Reflection).contains(Foo.Reflection.Fields.id))

        // projecting an implementing type merges selections of impl and interface
        ss = mk(Node.Reflection, "id ... on Foo { fooSelf { id } }")
        ss.selectionSetFor(Foo.Reflection).let {
            assertTrue(it.contains(Foo.Reflection.Fields.id))
            assertTrue(it.contains(Foo.Reflection.Fields.fooSelf))
        }
    }

    @Test
    fun `selectionSetFor type -- union`() {
        // self projections return same selection set
        var ss: SelectionSetImpl<FooOrBar> = mk(FooOrBar.Reflection, "__typename @skip(if:true)")
        assertEquals(ss, ss.selectionSetFor(FooOrBar.Reflection))

        // a member type can be projected even without type conditions
        // and will inherit __typename selection
        ss = mk(FooOrBar.Reflection, "__typename")
        assertFalse(ss.selectionSetFor(Foo.Reflection).isEmpty())

        // a member can be projected with type conditions
        ss = mk(FooOrBar.Reflection, "... on Foo { id }")
        assertTrue(ss.selectionSetFor(Foo.Reflection).contains(Foo.Reflection.Fields.id))
    }

    @Test
    fun isEmpty() {
        // all fields are skipped
        assertTrue(mk(Node.Reflection, "__typename @skip(if:true)").isEmpty())

        // skipped conditionless inline fragment
        assertTrue(mk(Node.Reflection, "... @skip(if:true) { id }").isEmpty())

        // empty inline fragment
        assertTrue(mk(Node.Reflection, "... { id @skip(if:true) }").isEmpty())

        // empty fragment spread
        assertTrue(
            mk(
                Node.Reflection,
                """
                    fragment Frag on Node { id @skip(if:true) }
                    fragment Main on Node { ... Frag }
                """
            ).isEmpty()
        )

        // non-empty field selections
        assertFalse(mk(Node.Reflection, "__typename").isEmpty())

        // non-empty inline fragments
        assertFalse(mk(Node.Reflection, "... { id }").isEmpty())

        // non-empty inline fragments
        assertFalse(mk(Node.Reflection, "... { id }").isEmpty())

        // non-empty fragment spreads
        assertFalse(
            mk(
                Node.Reflection,
                """
                    fragment Frag on Node { id }
                    fragment Main on Node { ... Frag }
                """
            ).isEmpty()
        )
    }

    @Test
    fun type() {
        mk(Node.Reflection, "__typename").also { it ->
            assertEquals(Node.Reflection, it.type)
            it.selectionSetFor(Foo.Reflection).also {
                assertEquals(Foo.Reflection, it.type)

                assertEquals(Node.Reflection, it.selectionSetFor(Foo.Reflection.Fields.nodeSelf).type)
                assertEquals(Foo.Reflection, it.selectionSetFor(Foo.Reflection.Fields.fooSelf).type)
            }
        }
    }
}
