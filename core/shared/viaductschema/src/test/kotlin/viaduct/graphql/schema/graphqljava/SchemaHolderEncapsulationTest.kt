package viaduct.graphql.schema.graphqljava

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromGraphQLSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.utils.collections.HMap

class SchemaHolderEncapsulationTest {
    private val sdl =
        """
        directive @tag(value: String!) on FIELD_DEFINITION

        scalar Date
        enum Role { USER }
        input Filter { date: Date }
        interface Node { id: ID! }
        type User implements Node { id: ID!, role(filter: Filter): Role }
        union SearchResult = User
        type Query { search(filter: Filter): [SearchResult!]! @tag(value: "query") }
        """.trimIndent()

    @Test
    fun `implementation data is not available through the default key`() {
        val validated = ViaductSchema.fromGraphQLSchema(readTypes(sdl))
        val raw = ViaductSchema.fromTypeDefinitionRegistry(readTypes(sdl))
        val filtered = validated.filter(NoopSchemaFilter())

        listOf(validated, raw, filtered).forEach { schema ->
            schema.definitions().forEach { def ->
                assertThrows(NoSuchElementException::class.java) {
                    def.holder[HMap.Key.DEFAULT]
                }
            }
        }
    }

    private fun ViaductSchema.definitions(): List<ViaductSchema.Def> =
        buildList {
            directives.values.forEach { directive ->
                add(directive)
                addAll(directive.args)
            }
            types.values.forEach { type ->
                add(type)
                when (type) {
                    is ViaductSchema.Enum -> addAll(type.values)
                    is ViaductSchema.Record ->
                        type.fields.forEach { field ->
                            add(field)
                            addAll(field.args)
                        }
                    else -> {}
                }
            }
        }
}
