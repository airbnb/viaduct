package viaduct.graphql.schema.test

import graphql.schema.GraphQLObjectType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
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
        val viaductExtendedSchema = mkSchema(sdl)
        assertEquals(ViaductSchema.TypeDefKind.OBJECT, viaductExtendedSchema.types["Foo"]?.kind)
    }

    @Test
    fun testMkGraphqlSchema() {
        val graphqlSchema = mkGraphQLSchema(sdl)
        val namedElement = graphqlSchema.getTypes<GraphQLObjectType>(listOf("Foo"))
        assertTrue(namedElement.isNotEmpty())
    }

    @Test
    fun `loading schema should fail with invalid pkg provided`() {
        val exception: Exception = assertThrows(
            IllegalStateException::class.java
        ) {
            loadGraphQLSchema()
        }
        // invalidschemapkg defined in bazel as env variable.
        assertEquals("Could not find any graphqls files in the classpath (invalidschemapkg)", exception.message)
    }
}
