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
    fun `loading schema should fail with invalid pkg provided`() {
        val exception: Exception = assertThrows(
            IllegalStateException::class.java
        ) {
            loadGraphQLSchema()
        }
        // invalidschemapkg defined in bazel as env variable.
        assertEquals("Could not find any graphqls files in the classpath (invalidschemapkg)", exception.message)
    }

    @Test
    fun `schema resource discovery finds graphqls resources under graphql path`() {
        val resources = findGraphQLSchemaResources("graphql").map { it.path }

        assertTrue(resources.any { it.contains("graphql/classgraph-included.graphqls") })
    }

    @Test
    fun `schema resource discovery excludes non-production schema module paths`() {
        val resources = findGraphQLSchemaResources("graphql").map { it.path }

        assertTrue(resources.any { it.contains("graphql/classgraph-included.graphqls") })
        assertTrue(resources.none { it.contains("graphql/testfixtures/classgraph-excluded.graphqls") })
        assertTrue(resources.none { it.contains("graphql/data/codelab/classgraph-excluded.graphqls") })
        assertTrue(resources.none { it.contains("graphql/presentation/codelab/classgraph-excluded.graphqls") })
    }
}
