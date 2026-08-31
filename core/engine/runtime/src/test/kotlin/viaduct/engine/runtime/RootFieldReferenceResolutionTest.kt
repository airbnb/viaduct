package viaduct.engine.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Caller
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.graphql.test.assertJson

@OptIn(ExperimentalCoroutinesApi::class)
class RootFieldReferenceResolutionTest {
    @Test
    fun `caller is derived from resolver object traversal`() {
        var caller: Caller? = null

        EngineTestModule(
            """
            type UGCText {
                localizedString: String @resolver
            }
            extend type Query {
                description: UGCText @resolver
            }
        """
        ) {
            field("UGCText" to "localizedString") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        caller = ctx.fieldScope.caller
                        "localized"
                    }
                }
            }
            field("Query" to "description") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("UGCText"),
                            emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ description { localizedString } }")
                .assertJson("""{"data": {"description": {"localizedString": "localized"}}}""")
        }

        assertEquals(
            Caller(null, "Query", "description"),
            caller,
        )
    }

    @Test
    fun `factory backing data remains available to child resolver RSS without duplicate execution`() {
        val factoryCalls = AtomicInteger()
        val localizedStringCalls = AtomicInteger()
        var factoryCaller: Caller? = null
        var localizedStringCaller: Caller? = null

        EngineTestModule(
            """
            type UGCText {
                translationConfig: String
                localizedString: String @resolver
            }
            type UGCTextFactory @namespaceType {
                create: UGCText @resolver
            }
            extend type Query {
                ugcTextFactory: UGCTextFactory
                description: UGCText @resolver
            }
        """
        ) {
            field("UGCTextFactory" to "create") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        factoryCalls.incrementAndGet()
                        factoryCaller = ctx.fieldScope.caller
                        createEngineObjectData(
                            schema.schema.getObjectType("UGCText"),
                            mapOf("translationConfig" to "French")
                        )
                    }
                }
            }
            field("UGCText" to "localizedString") {
                resolver {
                    objectSelections("translationConfig")
                    fn { _, obj, _, _, ctx ->
                        localizedStringCalls.incrementAndGet()
                        localizedStringCaller = ctx.fieldScope.caller
                        "Localized with ${obj.fetchAs<String>("translationConfig")}"
                    }
                }
            }
            field("Query" to "description") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("ugcTextFactory", "create"),
                            type = schema.schema.getObjectType("UGCText"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ description { localizedString } }")
                .assertJson("""{"data": {"description": {"localizedString": "Localized with French"}}}""")
        }

        assertEquals(1, factoryCalls.get())
        assertEquals(1, localizedStringCalls.get())
        val expectedCaller = Caller(
            tenantName = null,
            typeName = "Query",
            fieldName = "description",
        )
        assertEquals(expectedCaller, factoryCaller)
        assertEquals(expectedCaller, localizedStringCaller)
    }

    @Test
    fun `caller survives a chain of resolver RSS dependencies`() {
        val callers = ConcurrentHashMap<String, Caller>()

        EngineTestModule(
            """
            type UGCText {
                sourceText: String
                normalizedText: String @resolver
                renderedText: String @resolver
                localizedString: String @resolver
            }
            type UGCTextFactory @namespaceType {
                create: UGCText @resolver
            }
            extend type Query {
                ugcTextFactory: UGCTextFactory
                description: UGCText @resolver
            }
        """
        ) {
            field("UGCTextFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("UGCText"),
                            mapOf("sourceText" to "hello"),
                        )
                    }
                }
            }
            field("UGCText" to "normalizedText") {
                resolver {
                    objectSelections("sourceText")
                    fn { _, obj, _, _, ctx ->
                        callers["normalizedText"] = requireNotNull(ctx.fieldScope.caller)
                        obj.fetchAs<String>("sourceText").uppercase()
                    }
                }
            }
            field("UGCText" to "renderedText") {
                resolver {
                    objectSelections("normalizedText")
                    fn { _, obj, _, _, ctx ->
                        callers["renderedText"] = requireNotNull(ctx.fieldScope.caller)
                        "[${obj.fetchAs<String>("normalizedText")}]"
                    }
                }
            }
            field("UGCText" to "localizedString") {
                resolver {
                    objectSelections("renderedText")
                    fn { _, obj, _, _, ctx ->
                        callers["localizedString"] = requireNotNull(ctx.fieldScope.caller)
                        obj.fetchAs<String>("renderedText")
                    }
                }
            }
            field("Query" to "description") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("ugcTextFactory", "create"),
                            type = schema.schema.getObjectType("UGCText"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ description { localizedString } }")
                .assertJson("""{"data": {"description": {"localizedString": "[HELLO]"}}}""")
        }

        val expectedCaller = Caller(
            tenantName = null,
            typeName = "Query",
            fieldName = "description",
        )
        assertEquals(
            mapOf(
                "normalizedText" to expectedCaller,
                "renderedText" to expectedCaller,
                "localizedString" to expectedCaller,
            ),
            callers,
        )
    }

    @Test
    fun `factory function with nested namespace types resolves correctly`() {
        EngineTestModule(
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
        EngineTestModule(
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
    fun `factory field access check failure is propagated`() {
        EngineTestModule(
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
                        createEngineObjectData(
                            schema.schema.getObjectType("Item"),
                            mapOf("label" to "restricted")
                        )
                    }
                }
                checker {
                    fn { _, _ ->
                        throw SecurityException("factory access denied")
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
            val error = result.errors.single()
            assertEquals(listOf("item"), error.path)
            assertTrue(error.message.contains("factory access denied"))
        }
    }

    @Test
    fun `namespace access check failure prevents factory field execution`() {
        val factoryCalls = AtomicInteger()

        EngineTestModule(
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
            field("Query" to "itemFactory") {
                checker {
                    fn { _, _ ->
                        throw SecurityException("namespace access denied")
                    }
                }
            }
            field("ItemFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        factoryCalls.incrementAndGet()
                        createEngineObjectData(
                            schema.schema.getObjectType("Item"),
                            mapOf("label" to "restricted")
                        )
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
            val error = result.errors.single()
            assertEquals(listOf("item"), error.path)
            assertTrue(error.message.contains("namespace access denied"))
        }

        assertEquals(0, factoryCalls.get())
    }

    @Test
    fun `equivalent factory references share execution while distinct arguments resolve independently`() {
        val factoryCalls = AtomicInteger()

        EngineTestModule(
            """
            type Color {
                name: String
            }
            type ColorFactory @namespaceType {
                create(name: String!): Color @resolver
            }
            extend type Query {
                colorFactory: ColorFactory
                colors: [Color] @resolver
            }
        """
        ) {
            field("ColorFactory" to "create") {
                resolver {
                    fn { args, _, _, _, _ ->
                        factoryCalls.incrementAndGet()
                        createEngineObjectData(
                            schema.schema.getObjectType("Color"),
                            mapOf("name" to args.getAs<String>("name"))
                        )
                    }
                }
            }
            field("Query" to "colors") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        listOf(
                            ctx.createRootFieldReference(
                                rootFieldPath = listOf("colorFactory", "create"),
                                type = schema.schema.getObjectType("Color"),
                                args = mapOf("name" to "Red"),
                            ),
                            ctx.createRootFieldReference(
                                rootFieldPath = listOf("colorFactory", "create"),
                                type = schema.schema.getObjectType("Color"),
                                args = mapOf("name" to "Red"),
                            ),
                            ctx.createRootFieldReference(
                                rootFieldPath = listOf("colorFactory", "create"),
                                type = schema.schema.getObjectType("Color"),
                                args = mapOf("name" to "Blue"),
                            ),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ colors { name } }")
                .assertJson("""{"data": {"colors": [{"name": "Red"}, {"name": "Red"}, {"name": "Blue"}]}}""")
        }

        assertEquals(2, factoryCalls.get())
    }

    @Test
    fun `factory function alongside node reference`() {
        EngineTestModule(
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
        EngineTestModule(
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
    fun `nested root field reference failure preserves sibling fields`() {
        EngineTestModule(
            """
            type Texture {
                name: String
            }
            type TextureFactory @namespaceType {
                create: Texture @resolver
            }
            type Material {
                title: String
                texture: Texture
            }
            extend type Query {
                textureFactory: TextureFactory
                material: Material @resolver
            }
        """
        ) {
            field("TextureFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        throw RuntimeException("texture factory failed")
                    }
                }
            }
            field("Query" to "material") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Material"),
                            mapOf(
                                "title" to "Brushed Metal",
                                "texture" to ctx.createRootFieldReference(
                                    rootFieldPath = listOf("textureFactory", "create"),
                                    type = schema.schema.getObjectType("Texture"),
                                    args = emptyMap(),
                                )
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ material { title texture { name } } }")
            val data = result.getData<Map<String, Any?>>()
            val material = data["material"] as Map<*, *>
            assertEquals("Brushed Metal", material["title"])
            assertEquals(null, material["texture"])
            assertTrue(result.errors.any { it.path == listOf("material", "texture") })
        }
    }

    @Test
    fun `concurrent root field references with one failure and one success`() {
        EngineTestModule(
            """
            type Book {
                title: String
            }
            type BookFactory @namespaceType {
                create: Book @resolver
            }
            type Movie {
                name: String
            }
            type MovieFactory @namespaceType {
                create: Movie @resolver
            }
            extend type Query {
                bookFactory: BookFactory
                movieFactory: MovieFactory
                book: Book @resolver
                movie: Movie @resolver
            }
        """
        ) {
            field("BookFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Book"),
                            mapOf("title" to "Dune")
                        )
                    }
                }
            }
            field("MovieFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        throw RuntimeException("movie factory failed")
                    }
                }
            }
            field("Query" to "book") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("bookFactory", "create"),
                            type = schema.schema.getObjectType("Book"),
                            args = emptyMap(),
                        )
                    }
                }
            }
            field("Query" to "movie") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("movieFactory", "create"),
                            type = schema.schema.getObjectType("Movie"),
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ book { title } movie { name } }")
            val data = result.getData<Map<String, Any?>>()
            assertEquals(mapOf("title" to "Dune"), data["book"])
            assertEquals(null, data["movie"])
            assertTrue(result.errors.any { it.path == listOf("movie") })
        }
    }

    @Test
    fun `factory function returns data with explicit null fields`() {
        EngineTestModule(
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
                            mapOf("name" to "Widget", "price" to null)
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
            runQuery("{ product { name price } }")
                .assertJson("""{"data": {"product": {"name": "Widget", "price": null}}}""")
        }
    }

    @Test
    fun `operation variables in directives and field args are forwarded through root field reference`() {
        EngineTestModule(
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
        EngineTestModule(
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
        EngineTestModule(
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
                    fn { args, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Product"),
                            mapOf("name" to "Widget-${args.getAs<Int>("limit")}")
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
            ).assertJson("""{"data": {"product": {"name": "Widget-999", "reviews": [{"text": "Review 1"}, {"text": "Review 2"}]}}}""")
        }
    }

    @Test
    fun `querySelections with variable referencing root field reference`() {
        EngineTestModule(
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
        EngineTestModule(
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
    fun `node resolver returns a root field reference`() {
        EngineTestModule(
            """
            type Foo implements Node {
                id: ID!
                x: Int
            }
            extend type Query {
                foo1: Foo @resolver
                foo2: Foo @resolver
            }
        """
        ) {
            field("Query" to "foo1") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createNodeReference("0", schema.schema.getObjectType("Foo"))
                    }
                }
            }
            field("Query" to "foo2") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Foo"),
                            mapOf("x" to 2),
                        )
                    }
                }
            }
            type("Foo") {
                nodeUnbatchedExecutor { _, _, ctx ->
                    ctx.createRootFieldReference(
                        rootFieldPath = listOf("foo2"),
                        type = objectType,
                        args = emptyMap(),
                    ) as EngineObjectData
                }
            }
        }.runFeatureTest {
            runQuery("{ foo1 { x } }")
                .assertJson("{data: {foo1: {x: 2}}}")
        }
    }

    @Test
    fun `factory returns a root field reference`() {
        EngineTestModule(
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
    fun `factory resolver reads object and query selection sets`() {
        EngineTestModule(
            """
            type Product {
                objectName: String
                queryName: String
            }
            type ProductFactory @namespaceType {
                defaultName: String @resolver
                create: Product @resolver
            }
            extend type Query {
                defaultProductName: String @resolver
                productFactory: ProductFactory
                product: Product @resolver
            }
        """
        ) {
            fieldWithValue("ProductFactory" to "defaultName", "DefaultWidget")
            fieldWithValue("Query" to "defaultProductName", "QueryWidget")
            field("ProductFactory" to "create") {
                resolver {
                    objectSelections("defaultName")
                    querySelections("defaultProductName")
                    fn { _, obj, qry, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Product"),
                            mapOf(
                                "objectName" to obj.fetchAs<String>("defaultName"),
                                "queryName" to qry.fetchAs<String>("defaultProductName"),
                            )
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
            runQuery("{ product { objectName queryName } }")
                .assertJson("""{"data": {"product": {"objectName": "DefaultWidget", "queryName": "QueryWidget"}}}""")
        }
    }

    @Test
    fun `root field reference resolves to null`() {
        EngineTestModule(
            """
            type Widget implements Node {
                id: ID!
                name: String
            }
            type WidgetFactory @namespaceType {
                create: Widget @resolver
            }
            extend type Query {
                widgetFactory: WidgetFactory
                widget: Widget @resolver
            }
        """
        ) {
            fieldWithValue("WidgetFactory" to "create", null)
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
            type("Widget") {
                checker {
                    objectSelections("default", "id")
                    fn { _, objectDataMap ->
                        val objectData = objectDataMap["default"]!!
                        objectData.fetch("id") as? String
                            ?: throw IllegalArgumentException("No ID present for access check")
                        CheckerResult.Success
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ widget { name } }")
            assertEquals(mapOf("widget" to null), result.getData())
            assertTrue(result.errors.isEmpty()) { "Expected no errors but got: ${result.errors.map { "${it.path}: ${it.message}" }}" }
        }
    }

    @Test
    fun `root field reference to non-null field resolves to null propagates field error`() {
        EngineTestModule(
            """
            type Widget {
                name: String
            }
            type WidgetFactory @namespaceType {
                create: Widget @resolver
            }
            extend type Query {
                widgetFactory: WidgetFactory
                widget: Widget! @resolver
            }
        """
        ) {
            fieldWithValue("WidgetFactory" to "create", null)
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
            type("Widget") {
                checker {
                    fn { _, _ -> CheckerResult.Success }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ widget { name } }")
            assertNull(result.getData())
            assertTrue(result.errors.isNotEmpty()) { "Expected field error for non-null field resolving to null" }
            assertEquals(listOf("widget"), result.errors.first().path)
        }
    }
}
