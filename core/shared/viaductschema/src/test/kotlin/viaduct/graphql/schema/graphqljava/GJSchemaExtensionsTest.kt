package viaduct.graphql.schema.graphqljava

import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

class GJSchemaExtensionsTest {
    private val sdl =
        """
        directive @tag on OBJECT

        scalar Date
        enum Role { USER }
        input Filter { date: Date }
        interface Node { id: ID! }
        type User implements Node @tag { id: ID!, role(filter: Filter): Role }
        union SearchResult = User
        type Query { search(filter: Filter): [SearchResult!]! }
        """.trimIndent()

    @Test
    fun `getters return underlying graphql-java definitions`() {
        val graphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(readTypes(sdl))
        val schema = ViaductSchema.fromGraphQLSchema(graphQLSchema)

        assertSame(graphQLSchema.getType("Date"), (schema.types.getValue("Date") as ViaductSchema.Scalar).gjDef)
        assertSame(graphQLSchema.getType("Role"), (schema.types.getValue("Role") as ViaductSchema.Enum).gjDef)
        assertSame(graphQLSchema.getType("SearchResult"), (schema.types.getValue("SearchResult") as ViaductSchema.Union).gjDef)
        assertSame(graphQLSchema.getType("Node"), (schema.types.getValue("Node") as ViaductSchema.Interface).gjDef)
        assertSame(graphQLSchema.getType("User"), (schema.types.getValue("User") as ViaductSchema.Object).gjDef)
        assertSame(graphQLSchema.getType("Filter"), (schema.types.getValue("Filter") as ViaductSchema.Input).gjDef)
        assertSame(graphQLSchema.getDirective("tag"), schema.directives.getValue("tag").gjDef)

        val def: ViaductSchema.Def = schema.types.getValue("User")
        assertSame(graphQLSchema.getType("User"), def.gjDef)
    }

    @Test
    fun `getters reject definitions from other schema representations`() {
        val raw = ViaductSchema.fromTypeDefinitionRegistry(readTypes(sdl))
        val filtered = ViaductSchema.fromGraphQLSchema(readTypes(sdl)).filter(NoopSchemaFilter())

        assertThrows(NoSuchElementException::class.java) {
            raw.types.getValue("User").gjDef
        }
        assertThrows(NoSuchElementException::class.java) {
            filtered.types.getValue("User").gjDef
        }
    }
}
