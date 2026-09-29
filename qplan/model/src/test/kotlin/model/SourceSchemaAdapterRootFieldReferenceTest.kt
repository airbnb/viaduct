package model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import model.testing.TestWorld

class SourceSchemaAdapterRootFieldReferenceTest {
    @Test
    fun `lowers a source root path and preserves target arguments`() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
        val adapter = SourceSchemaAdapter(schema)

        val reference =
            adapter.lowerRootFieldReference(
                rootFieldPath = listOf("productFactory", "get"),
                sourceTypeName = "Product",
                arguments = mapOf("id" to "p1"),
            )

        assertEquals(
            listOf(
                schema.requireObjectField("Query", "productFactory"),
                schema.requireObjectField("ProductFactory", "get"),
            ),
            reference.path,
        )
        assertEquals("Product", reference.type.name)
        assertEquals(mapOf("id" to "p1"), reference.arguments.fieldValues)
    }

    @Test
    fun `rejects an unknown path and mismatched source type`() {
        val adapter = SourceSchemaAdapter(TestWorld.fromSDL(SCHEMA_SDL).schema)

        assertFailsWith<IllegalArgumentException> {
            adapter.lowerRootFieldReference(listOf("missing"), "Product", emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            adapter.lowerRootFieldReference(
                listOf("productFactory", "get"),
                "ProductFactory",
                mapOf("id" to "p1"),
            )
        }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            interface Node {
              id: ID!
            }

            type Product implements Node {
              id: ID!
              name: String
            }

            type ProductFactory {
              get(id: ID!): Product
            }

            type Query {
              productFactory: ProductFactory
            }
            """.trimIndent()
    }
}
