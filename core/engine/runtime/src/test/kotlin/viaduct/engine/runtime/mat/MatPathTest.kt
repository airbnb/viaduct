package viaduct.engine.runtime.mat

import graphql.schema.GraphQLObjectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.result.ObjectEngineResult

class MatPathTest {
    private val rootType = objectType("Root")
    private val childType = objectType("Child")
    private val grandchildType = objectType("Grandchild")

    @Test
    fun `terminal type is root type for an empty path`() {
        val path = MatPath(rootType)

        assertEquals(emptyList<MatPath.Segment>(), path.segments)
        assertSame(rootType, path.terminalType)
    }

    @Test
    fun `terminal type is the last segment type`() {
        val child = MatPath.Segment(
            type = childType,
            key = ObjectEngineResult.Key(
                "child",
                alias = "childAlias",
                arguments = mapOf("id" to 1),
            ),
        )
        val grandchild = MatPath.Segment(
            type = grandchildType,
            key = ObjectEngineResult.Key("grandchild"),
            indices = listOf(1, 2),
        )

        val path = MatPath(rootType, listOf(child, grandchild))

        assertEquals(listOf(child, grandchild), path.segments)
        assertSame(grandchildType, path.terminalType)
    }

    private fun objectType(name: String): GraphQLObjectType =
        GraphQLObjectType.newObject()
            .name(name)
            .build()
}
