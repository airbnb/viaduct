package viaduct.engine.runtime2.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import viaduct.engine.runtime2.model.testing.TestWorld

class RootFieldReferenceDataTest {
    private val schema = TestWorld.fromSDL(SCHEMA_SDL).schema
    private val query = schema.requireQueryTypeDef()
    private val factory = schema.requireType("ProductFactory") as viaduct.graphql.schema.ViaductSchema.Object
    private val factoryField = schema.requireObjectField("Query", "factory")
    private val createField = schema.requireObjectField("ProductFactory", "create")

    @Test
    fun `factory validates and snapshots a Query-rooted path`() {
        val mutablePath = mutableListOf(factoryField, createField)
        val reference = RootFieldReferenceData.of(mutablePath, mapOf("name" to "chair"))
        mutablePath.clear()

        assertEquals(listOf(factoryField, createField), reference.path)
        assertEquals(createField, reference.targetField)
        assertEquals(schema.requireType("Product"), reference.type)
        assertEquals(mapOf("name" to "chair"), reference.arguments.fieldValues)
        assertEquals(
            reference,
            RootFieldReferenceData.of(listOf(factoryField, createField), mapOf("name" to "chair")),
        )
        assertNotSame(
            reference,
            RootFieldReferenceData.of(listOf(factoryField, createField), mapOf("name" to "chair")),
        )
    }

    @Test
    fun `factory accepts already resolved target arguments`() {
        val arguments = Arguments.Resolved.of(createField, mapOf("name" to "chair"))

        val reference = RootFieldReferenceData.of(listOf(factoryField, createField), arguments)

        assertSame(arguments, reference.arguments)
    }

    @Test
    fun `factory accepts interface and union targets`() {
        val item = schema.requireObjectField("ProductFactory", "item")
        val search = schema.requireObjectField("ProductFactory", "search")

        assertEquals(
            schema.requireType("Item"),
            RootFieldReferenceData.of(listOf(factoryField, item), emptyMap()).type,
        )
        assertEquals(
            schema.requireType("SearchResult"),
            RootFieldReferenceData.of(listOf(factoryField, search), emptyMap()).type,
        )
    }

    @Test
    fun `reference conforms as object output including within a list`() {
        val reference =
            RootFieldReferenceData.of(
                listOf(factoryField, createField),
                mapOf("name" to "chair"),
            )

        val data =
            engineObjectDataOf(
                query,
                mapOf(
                    "owner" to reference,
                    "products" to listOf(reference),
                ),
            )

        assertSame(reference, data.outputValue("owner"))
        assertSame(reference, (data.outputValue("products") as List<*>).single())
        assertFailsWith<IllegalArgumentException> {
            engineObjectDataOf(query, mapOf("other" to reference))
        }
    }

    @Test
    fun `rejects malformed paths targets and arguments`() {
        assertFailsWith<IllegalArgumentException> {
            RootFieldReferenceData.of(emptyList(), emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            RootFieldReferenceData.of(listOf(createField), mapOf("name" to "chair"))
        }
        assertFailsWith<IllegalArgumentException> {
            RootFieldReferenceData.of(
                listOf(factoryField, schema.requireObjectField("Other", "product")),
                emptyMap(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RootFieldReferenceData.of(
                listOf(schema.requireObjectField("Query", "products")),
                emptyMap(),
            )
        }
        assertFailsWith<ClassCastException> {
            RootFieldReferenceData.of(listOf(factoryField, createField), mapOf("name" to 1))
        }
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type Product implements Item {
              name: String
            }

            type Service implements Item {
              name: String
            }

            interface Item {
              name: String
            }

            union SearchResult = Product | Service

            type ProductFactory {
              create(name: String!): Product!
              item: Item
              search: SearchResult
            }

            type Other {
              product: Product
            }

            type Query {
              factory: ProductFactory
              owner: Product
              products: [Product]
              other: Other
            }
            """.trimIndent()
    }
}
