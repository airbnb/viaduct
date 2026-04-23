package viaduct.engine.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.graphql.test.assertJson

@OptIn(ExperimentalCoroutinesApi::class)
class RootFieldReferenceResolutionTest {
    @Test
    fun `factory function with nested namespace types resolves correctly`() {
        MockTenantModuleBootstrapper(
            """
            type Product {
                name: String
                price: Int
            }
            type ProductFactory @namespaceType {
                create: Product @resolver
            }
            type Factories @namespaceType {
                products: ProductFactory
            }
            extend type Query {
                _factories: Factories
                product: Product @resolver
            }
        """
        ) {
            field("ProductFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Product"),
                            mapOf("name" to "Widget", "price" to 42)
                        )
                    }
                }
            }
            field("Query" to "product") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("_factories", "products", "create"),
                            type = schema.schema.getObjectType("Product"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ product { name price } }")
                .assertJson("""{"data": {"product": {"name": "Widget", "price": 42}}}""")
        }
    }

    @Test
    fun `factory function error propagation`() {
        MockTenantModuleBootstrapper(
            """
            type Item {
                label: String
            }
            type ItemFactory @namespaceType {
                create: Item @resolver
            }
            extend type Query {
                itemFactory: ItemFactory
                item: Item @resolver
            }
        """
        ) {
            field("ItemFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        throw RuntimeException("factory resolution failed")
                    }
                }
            }
            field("Query" to "item") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("itemFactory", "create"),
                            type = schema.schema.getObjectType("Item"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ item { label } }")
            assertEquals(mapOf("item" to null), result.getData())
            assertTrue(result.errors.any { it.path == listOf("item") })
        }
    }

    @Test
    fun `factory function alongside node reference`() {
        MockTenantModuleBootstrapper(
            """
            type Widget {
                label: String
                weight: Int
            }
            type WidgetFactory @namespaceType {
                create: Widget @resolver
            }
            type Gadget implements Node {
                id: ID!
                model: String
            }
            extend type Query {
                widgetFactory: WidgetFactory
                widget: Widget @resolver
                gadget: Gadget @resolver
            }
        """
        ) {
            field("WidgetFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Widget"),
                            mapOf("label" to "Sprocket", "weight" to 10)
                        )
                    }
                }
            }
            field("Query" to "widget") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("widgetFactory", "create"),
                            type = schema.schema.getObjectType("Widget"),
                            args = emptyMap(),
                        )
                    }
                }
            }
            field("Query" to "gadget") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("99", schema.schema.getObjectType("Gadget"))
                    }
                }
            }
            type("Gadget") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id, "model" to "G-$id")
                    )
                }
            }
        }.runFeatureTest {
            runQuery("{ widget { label weight } gadget { id model } }")
                .assertJson("""{"data": {"widget": {"label": "Sprocket", "weight": 10}, "gadget": {"id": "99", "model": "G-99"}}}""")
        }
    }

    @Test
    fun `root field reference nested inside resolver response`() {
        MockTenantModuleBootstrapper(
            """
            type Color {
                name: String
            }
            type ColorFactory @namespaceType {
                create: Color @resolver
            }
            type Painting {
                title: String
                color: Color
            }
            extend type Query {
                colorFactory: ColorFactory
                painting: Painting @resolver
            }
        """
        ) {
            field("ColorFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Color"),
                            mapOf("name" to "Red")
                        )
                    }
                }
            }
            field("Query" to "painting") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Painting"),
                            mapOf(
                                "title" to "Sunset",
                                "color" to ctx.createRootFieldReference(
                                    rootFieldPath = listOf("colorFactory", "create"),
                                    type = schema.schema.getObjectType("Color"),
                                    args = emptyMap(),
                                )
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ painting { title color { name } } }")
                .assertJson("""{"data": {"painting": {"title": "Sunset", "color": {"name": "Red"}}}}""")
        }
    }

    @Test
    fun `operation variables in directives and field args are forwarded through root field reference`() {
        MockTenantModuleBootstrapper(
            """
            type Review {
                text: String
            }
            type Product {
                name: String
                price: Int
                reviews(limit: Int!): [Review] @resolver
            }
            type ProductFactory @namespaceType {
                create: Product @resolver
            }
            extend type Query {
                productFactory: ProductFactory
                product: Product @resolver
            }
        """
        ) {
            field("ProductFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Product"),
                            mapOf("name" to "Widget", "price" to 42)
                        )
                    }
                }
            }
            field("Product" to "reviews") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val limit = args.getAs<Int>("limit")
                        (1..limit).map { i ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Review"),
                                mapOf("text" to "Review $i")
                            )
                        }
                    }
                }
            }
            field("Query" to "product") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("productFactory", "create"),
                            type = schema.schema.getObjectType("Product"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery(
                "query(\$includePrice: Boolean!, \$n: Int!) { product { name price @include(if: \$includePrice) reviews(limit: \$n) { text } } }",
                mapOf("includePrice" to true, "n" to 2),
            ).assertJson("""{"data": {"product": {"name": "Widget", "price": 42, "reviews": [{"text": "Review 1"}, {"text": "Review 2"}]}}}""")
        }
    }

    @Test
    fun `include directive variable excludes field through root field reference`() {
        MockTenantModuleBootstrapper(
            """
            type Product {
                name: String
                price: Int
            }
            type ProductFactory @namespaceType {
                create: Product @resolver
            }
            extend type Query {
                productFactory: ProductFactory
                product: Product @resolver
            }
        """
        ) {
            field("ProductFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Product"),
                            mapOf("name" to "Widget", "price" to 42)
                        )
                    }
                }
            }
            field("Query" to "product") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("productFactory", "create"),
                            type = schema.schema.getObjectType("Product"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery(
                "query(\$includePrice: Boolean!) { product { name price @include(if: \$includePrice) } }",
                mapOf("includePrice" to false),
            ).assertJson("""{"data": {"product": {"name": "Widget"}}}""")
        }
    }

    @Test
    fun `root field reference args do not collide with operation variables`() {
        MockTenantModuleBootstrapper(
            """
            type Review {
                text: String
            }
            type Product {
                name: String
                reviews(limit: Int!): [Review] @resolver
            }
            type ProductFactory @namespaceType {
                create(limit: Int!): Product @resolver
            }
            extend type Query {
                productFactory: ProductFactory
                product: Product @resolver
            }
        """
        ) {
            field("ProductFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Product"),
                            mapOf("name" to "Widget")
                        )
                    }
                }
            }
            field("Product" to "reviews") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val limit = args.getAs<Int>("limit")
                        (1..limit).map { i ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Review"),
                                mapOf("text" to "Review $i")
                            )
                        }
                    }
                }
            }
            field("Query" to "product") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        // Root field arg "limit" = 999 must not collide with the
                        // client operation variable $limit = 2.
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("productFactory", "create"),
                            type = schema.schema.getObjectType("Product"),
                            args = mapOf("limit" to 999),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery(
                "query(\$limit: Int!) { product { name reviews(limit: \$limit) { text } } }",
                mapOf("limit" to 2),
            ).assertJson("""{"data": {"product": {"name": "Widget", "reviews": [{"text": "Review 1"}, {"text": "Review 2"}]}}}""")
        }
    }

    @Test
    fun `querySelections with variable referencing root field reference`() {
        MockTenantModuleBootstrapper(
            """
            type Review {
                text: String
            }
            type Product {
                reviews(limit: Int!): [Review] @resolver
            }
            type ProductFactory @namespaceType {
                create: Product @resolver
            }
            extend type Query {
                productFactory: ProductFactory
                product: Product @resolver
                topReview: String @resolver
            }
        """
        ) {
            field("ProductFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Product"), emptyMap())
                    }
                }
            }
            field("Product" to "reviews") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val limit = args.getAs<Int>("limit")
                        (1..limit).map { i ->
                            createEngineObjectData(
                                schema.schema.getObjectType("Review"),
                                mapOf("text" to "Review $i")
                            )
                        }
                    }
                }
            }
            field("Query" to "product") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("productFactory", "create"),
                            type = schema.schema.getObjectType("Product"),
                            args = emptyMap(),
                        )
                    }
                }
            }
            field("Query" to "topReview") {
                resolver {
                    querySelections("product { reviews(limit: \$limit) { text } }") {
                        variables("limit") { _, _ -> mapOf("limit" to 3) }
                    }
                    fn { _, _, qry, _, _ ->
                        val product = qry.fetchAs<EngineObjectData>("product")
                        @Suppress("UNCHECKED_CAST")
                        val reviews = product.fetchAs<List<EngineObjectData>>("reviews")
                        "${reviews.size} reviews"
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ topReview }")
                .assertJson("""{"data": {"topReview": "3 reviews"}}""")
        }
    }

    @Test
    fun `factory returns a node reference`() {
        MockTenantModuleBootstrapper(
            """
            type Product implements Node {
                id: ID!
                name: String
            }
            type ProductFactory @namespaceType {
                get: Product @resolver
            }
            extend type Query {
                productFactory: ProductFactory
                product: Product @resolver
            }
        """
        ) {
            field("ProductFactory" to "get") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("p1", schema.schema.getObjectType("Product"))
                    }
                }
            }
            type("Product") {
                nodeUnbatchedExecutor { id, _, _ ->
                    createEngineObjectData(
                        objectType,
                        mapOf("id" to id, "name" to "Product-$id")
                    )
                }
            }
            field("Query" to "product") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("productFactory", "get"),
                            type = schema.schema.getObjectType("Product"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ product { id name } }")
                .assertJson("""{"data": {"product": {"id": "p1", "name": "Product-p1"}}}""")
        }
    }

    @Test
    fun `factory returns a root field reference`() {
        MockTenantModuleBootstrapper(
            """
            type Product {
                name: String
                price: Int
            }
            type ProductFactory @namespaceType {
                create: Product @resolver
            }
            type AliasFactory @namespaceType {
                alias: Product @resolver
            }
            extend type Query {
                productFactory: ProductFactory
                aliasFactory: AliasFactory
                product: Product @resolver
            }
        """
        ) {
            field("ProductFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Product"),
                            mapOf("name" to "Widget", "price" to 42)
                        )
                    }
                }
            }
            field("AliasFactory" to "alias") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("productFactory", "create"),
                            type = schema.schema.getObjectType("Product"),
                            args = emptyMap(),
                        )
                    }
                }
            }
            field("Query" to "product") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("aliasFactory", "alias"),
                            type = schema.schema.getObjectType("Product"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ product { name price } }")
                .assertJson("""{"data": {"product": {"name": "Widget", "price": 42}}}""")
        }
    }

    @Test
    fun `factory resolver with objectValueFragment reads sibling field`() {
        MockTenantModuleBootstrapper(
            """
            type Product {
                name: String
            }
            type ProductFactory @namespaceType {
                defaultName: String @resolver
                create: Product @resolver
            }
            extend type Query {
                productFactory: ProductFactory
                product: Product @resolver
            }
        """
        ) {
            field("ProductFactory" to "defaultName") {
                resolver {
                    fn { _, _, _, _, _ -> "DefaultWidget" }
                }
            }
            field("ProductFactory" to "create") {
                resolver {
                    objectSelections("defaultName")
                    fn { _, obj, _, _, _ ->
                        val defaultName = obj.fetchAs<String>("defaultName")
                        createEngineObjectData(
                            schema.schema.getObjectType("Product"),
                            mapOf("name" to defaultName)
                        )
                    }
                }
            }
            field("Query" to "product") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("productFactory", "create"),
                            type = schema.schema.getObjectType("Product"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ product { name } }")
                .assertJson("""{"data": {"product": {"name": "DefaultWidget"}}}""")
        }
    }
}
