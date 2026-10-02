package viaduct.graphql.schema.test

import graphql.schema.GraphQLObjectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema

internal class UtilsTest {
    private val sdl = """
        type Foo {
            bar: Int
        }
    """.trimIndent()

    @Test
    fun testMkSchema() {
        val viaductExtendedSchema = createSchema(sdl)
        assertEquals(ViaductSchema.TypeDefKind.OBJECT, viaductExtendedSchema.types["Foo"]?.kind)
    }

    @Test
    fun testMkGraphqlSchema() {
        val graphqlSchema = createGraphQLSchema(sdl)
        val namedElement = graphqlSchema.getTypes<GraphQLObjectType>(listOf("Foo"))
        assertTrue(namedElement.isNotEmpty())
    }

    @Test
    fun `loads only explicitly supplied schema resources`() {
        val schema = loadGraphQLSchema(listOf("graphql/first.graphqls", "graphql/second.graphqls"))

        assertTrue(schema.types.keys.containsAll(listOf("Query", "First", "Second")))
        assertEquals(setOf("first", "second"), (schema.types["Query"] as ViaductSchema.Object).fields.map { it.name }.toSet())
        assertTrue("Unlisted" !in schema.types)
    }

    @Test
    fun `rejects empty schema inputs`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            loadGraphQLSchema(emptyList())
        }
        assertEquals("schemaResourcePaths must not be empty", exception.message)
    }

    @Test
    fun `rejects a missing resource even when other inputs exist`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            loadGraphQLSchema(listOf("graphql/first.graphqls", "graphql/missing.graphqls"))
        }
        assertTrue(exception.message!!.contains("graphql/missing.graphqls"))
    }
}
