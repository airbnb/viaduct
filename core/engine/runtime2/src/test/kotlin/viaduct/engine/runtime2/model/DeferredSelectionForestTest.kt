package viaduct.engine.runtime2.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class DeferredSelectionForestTest {
    @Test
    fun `collection preserves ordinary forest operations without collecting child levels`() {
        val world = TestWorld.fromSDL("type Query { box: Box } type Box { value: Int }")
        val schema = world.schema
        val query = schema.requireQueryTypeDef()
        val box = schema.requireType("Box") as ViaductSchema.Object
        var rootCollections = 0
        var childCollections = 0
        val children = deferredSelectionForest {
            childCollections++
            selectionForestOf(Selection.of(ObjectEngineResult.Key.of(box.requireField("value"), emptyMap()), setOf(box), selectionForestOf()))
        }
        val selection = Selection.of(ObjectEngineResult.Key.of(query.requireField("box"), emptyMap()), setOf(query), children)
        val root = deferredSelectionForest {
            rootCollections++
            selectionForestOf(selection, selection)
        }
        assertEquals(0, rootCollections)
        assertEquals(2, root.size)
        assertTrue(root.all { it === selection })
        assertEquals(2, root.filter { true }.size)
        assertEquals(2, root.flatMap(::selectionForestOf).size)
        assertEquals(3, (root + selectionForestOf(selection)).size)
        assertEquals(1, root.merge(query).size)
        assertEquals(1, rootCollections)
        assertEquals(0, childCollections)
        assertSame(children, root.filter { true }.membersFirst().subselections)
        assertEquals(1, children.size)
        assertEquals(1, childCollections)
    }

    private fun SelectionForest.membersFirst(): Selection {
        var first: Selection? = null
        all {
            first = it
            false
        }
        return requireNotNull(first)
    }
}
