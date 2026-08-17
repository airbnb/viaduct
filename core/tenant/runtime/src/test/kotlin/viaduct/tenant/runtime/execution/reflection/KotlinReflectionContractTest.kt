@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.reflection

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.resolver.Resolver
import viaduct.api.select.SelectionSet
import viaduct.graphql.test.assertEquals
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.tenant.runtime.execution.reflection.resolverbases.CategoryResolvers
import viaduct.tenant.runtime.execution.reflection.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.reflection.resolverbases.OwnedSelectionChildResolvers
import viaduct.tenant.runtime.execution.reflection.resolverbases.OwnedSelectionNodeResolvers
import viaduct.tenant.runtime.execution.reflection.resolverbases.OwnedSelectionPayloadResolvers
import viaduct.tenant.runtime.execution.reflection.resolverbases.QueryResolvers
import viaduct.tenant.runtime.execution.reflection.resolverbases.ShelfResolvers

class KotlinReflectionContractTest : ReflectionContractTest() {
    private class OwnedSelectionsCapture {
        lateinit var field: SelectionSet<OwnedSelectionPayload>
        lateinit var node: SelectionSet<OwnedSelectionNode>
        var scalarSelectionsAreEmpty: Boolean = false
    }

    @Resolver
    class Query_CategoryResolver : QueryResolvers.Category() {
        override suspend fun resolve(ctx: Context) =
            Category.Builder(ctx).also { builder ->
                builder.put(Category.Fields.id.name, ctx.arguments.id)
            }.build()
    }

    @Resolver
    class Query_ShelfResolver : QueryResolvers.Shelf() {
        override suspend fun resolve(ctx: Context) = Shelf.Builder(ctx).build()
    }

    @Resolver
    class Shelf_TopProductResolver : ShelfResolvers.TopProduct() {
        override suspend fun resolve(ctx: Context): Product = Toy.Builder(ctx).id(1).prodType("action_figure").build()
    }

    @Resolver(
        """
        fragment _ on Shelf {
            topProduct {
                ... on Toy { id prodType }
                ... on Fruit { id prodType }
            }
        }
        """
    )
    class Shelf_TopProductDescriptionResolver : ShelfResolvers.TopProductDescription() {
        override suspend fun resolve(ctx: Context): String {
            val product = ctx.getObjectValue().getTopProduct()
            return when (product) {
                is Toy -> "Toy: ${product.getProdType()}"
                is Fruit -> "Fruit: ${product.getProdType()}"
                else -> "Unknown"
            }
        }
    }

    @Resolver
    class Category_ProductsResolver : CategoryResolvers.Products() {
        override suspend fun resolve(ctx: Context): List<Product> {
            val products = listOf<Product>(
                Toy.Builder(ctx).id(123).build(),
                Fruit.Builder(ctx).id(123).build()
            )
            return products.map { product ->
                if (ctx.selections().requestsType(Toy.Reflection) && product is Toy) {
                    Toy.Builder(ctx).id(product.getId()).prodType("Toy").build()
                } else if (ctx.selections().requestsType(Fruit.Reflection) && product is Fruit) {
                    Fruit.Builder(ctx).id(product.getId()).prodType("Fruit").build()
                } else {
                    product
                }
            }
        }
    }

    @Resolver
    class Query_OwnedSelectionPayloadResolver : QueryResolvers.OwnedSelectionPayload() {
        override suspend fun resolve(ctx: Context): OwnedSelectionPayload {
            (ctx.requestContext as OwnedSelectionsCapture).field = ctx.ownedSelections()
            return OwnedSelectionPayload.Builder(ctx)
                .local("payload-local")
                .child(OwnedSelectionChild.Builder(ctx).kept("child-kept").build())
                .contact(
                    OwnedSelectionLocalContact.Builder(ctx)
                        .label("contact-label")
                        .build()
                )
                .build()
        }
    }

    @Resolver
    class OwnedSelectionPayload_DelegatedResolver : OwnedSelectionPayloadResolvers.Delegated() {
        override suspend fun resolve(ctx: Context): String = "payload-delegated"
    }

    @Resolver
    class OwnedSelectionChild_DelegatedResolver : OwnedSelectionChildResolvers.Delegated() {
        override suspend fun resolve(ctx: Context): String = "child-delegated"
    }

    @Resolver
    class Query_OwnedSelectionNodeResolver : QueryResolvers.OwnedSelectionNode() {
        override suspend fun resolve(ctx: Context): OwnedSelectionNode = ctx.nodeRef(ctx.globalIDFor(OwnedSelectionNode.Reflection, "node-1"))
    }

    @Resolver
    class Query_OwnedSelectionScalarResolver : QueryResolvers.OwnedSelectionScalar() {
        override suspend fun resolve(ctx: Context): String {
            (ctx.requestContext as OwnedSelectionsCapture).scalarSelectionsAreEmpty =
                ctx.selections().isEmpty()
            return "scalar"
        }
    }

    @Resolver
    class OwnedSelectionNode_NodeResolver : NodeResolvers.OwnedSelectionNode() {
        override suspend fun resolve(ctx: Context): OwnedSelectionNode {
            (ctx.requestContext as OwnedSelectionsCapture).node = ctx.ownedSelections()
            return OwnedSelectionNode.Builder(ctx)
                .local("node-local")
                .build()
        }
    }

    @Resolver
    class OwnedSelectionNode_DelegatedResolver : OwnedSelectionNodeResolvers.Delegated() {
        override suspend fun resolve(ctx: Context): String = "node-delegated"
    }

    @Test
    fun `owned selections are available through the public API without MAT`() {
        assertOwnedSelections()
    }

    @Test
    fun `owned selections are available through the public API with MAT`() {
        withViaductBuilder {
            withFlagManager(
                MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION)
            )
        }

        assertOwnedSelections()
    }

    private fun assertOwnedSelections() {
        val capture = OwnedSelectionsCapture()
        execute(
            query = """
                query(${'$'}limit: Int!, ${'$'}skipLabel: Boolean!) {
                  ownedSelectionPayload {
                    renamedLocal: local
                    delegated
                    child(limit: ${'$'}limit) {
                      kept
                      delegated
                    }
                    contact {
                      label @skip(if: ${'$'}skipLabel)
                    }
                  }
                  ownedSelectionNode {
                    id
                    local
                    delegated
                  }
                  ownedSelectionScalar
                }
            """.trimIndent(),
            variables = mapOf(
                "limit" to 2,
                "skipLabel" to true,
            ),
            requestContext = capture,
        ).assertEquals {
            "data" to {
                "ownedSelectionPayload" to {
                    "renamedLocal" to "payload-local"
                    "delegated" to "payload-delegated"
                    "child" to {
                        "kept" to "child-kept"
                        "delegated" to "child-delegated"
                    }
                    "contact" to emptyMap<String, Any?>()
                }
                "ownedSelectionNode" to {
                    "id" to createGlobalIdString(OwnedSelectionNode.Reflection, "node-1")
                    "local" to "node-local"
                    "delegated" to "node-delegated"
                }
                "ownedSelectionScalar" to "scalar"
            }
        }

        assertTrue(capture.field.contains(OwnedSelectionPayload.Fields.local))
        assertFalse(capture.field.contains(OwnedSelectionPayload.Fields.delegated))
        val fieldFragment = capture.field.toFragment()
        assertEquals("Main", fieldFragment.name)
        fieldFragment.document shouldContain "fragment Main on OwnedSelectionPayload"
        fieldFragment.document shouldContain "renamedLocal"
        fieldFragment.document shouldContain "child"
        fieldFragment.document shouldContain "kept"
        fieldFragment.document shouldContain "contact"
        fieldFragment.document shouldContain "__typename"
        fieldFragment.document shouldContain "${'$'}limit"
        fieldFragment.document shouldNotContain "delegated"
        assertEquals(
            mapOf(
                "limit" to 2,
                "skipLabel" to true,
            ),
            fieldFragment.variables,
        )

        val child = capture.field.selectionSetFor(OwnedSelectionPayload.Fields.child)
        assertTrue(child.contains(OwnedSelectionChild.Fields.kept))
        assertFalse(child.contains(OwnedSelectionChild.Fields.delegated))

        assertFalse(capture.node.contains(OwnedSelectionNode.Fields.id))
        assertTrue(capture.node.contains(OwnedSelectionNode.Fields.local))
        assertFalse(capture.node.contains(OwnedSelectionNode.Fields.delegated))
        val nodeFragment = capture.node.toFragment()
        nodeFragment.document shouldContain "fragment Main on OwnedSelectionNode"
        nodeFragment.document shouldContain "local"
        nodeFragment.document shouldNotContain "delegated"
        assertTrue(capture.scalarSelectionsAreEmpty)
    }
}
