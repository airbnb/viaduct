package viaduct.engine.runtime

import graphql.schema.GraphQLCompositeType
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
    fun `factory function with interface type resolves to concrete type`() {
        MockTenantModuleBootstrapper(
            """
            interface Animal {
                name: String
            }
            type Dog implements Animal {
                name: String
                breed: String
            }
            type AnimalFactory @namespaceType {
                create: Animal @resolver
            }
            extend type Query {
                animalFactory: AnimalFactory
                animal: Animal @resolver
            }
        """
        ) {
            field("AnimalFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Dog"),
                            mapOf("name" to "Buddy", "breed" to "Golden")
                        )
                    }
                }
            }
            field("Query" to "animal") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("animalFactory", "create"),
                            type = schema.schema.getType("Animal") as GraphQLCompositeType,
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ animal { name ... on Dog { breed } } }")
                .assertJson("""{"data": {"animal": {"name": "Buddy", "breed": "Golden"}}}""")
        }
    }

    @Test
    fun `list of factory function references resolves correctly`() {
        MockTenantModuleBootstrapper(
            """
            type Color {
                name: String
                hex: String
            }
            type ColorFactory @namespaceType {
                create: Color @resolver
            }
            extend type Query {
                colorFactory: ColorFactory
                colors: [Color] @resolver
            }
        """
        ) {
            field("ColorFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Color"),
                            mapOf("name" to "Red", "hex" to "#FF0000")
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
                                args = emptyMap(),
                            ),
                            ctx.createRootFieldReference(
                                rootFieldPath = listOf("colorFactory", "create"),
                                type = schema.schema.getObjectType("Color"),
                                args = emptyMap(),
                            ),
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ colors { name hex } }")
                .assertJson("""{"data": {"colors": [{"name": "Red", "hex": "#FF0000"}, {"name": "Red", "hex": "#FF0000"}]}}""")
        }
    }

    @Test
    fun `failing abstract list item does not fail sibling fields`() {
        MockTenantModuleBootstrapper(
            """
            interface Shape {
                area: Int
            }
            type Circle implements Shape {
                area: Int
            }
            type ShapeFactory @namespaceType {
                create(shouldFail: Boolean!): Shape @resolver
            }
            type Gallery {
                title: String
                shapes: [Shape]
            }
            extend type Query {
                shapeFactory: ShapeFactory
                gallery: Gallery @resolver
            }
        """
        ) {
            field("ShapeFactory" to "create") {
                resolver {
                    fn { args, _, _, _, _ ->
                        if (args.getAs<Boolean>("shouldFail")) throw RuntimeException("factory failed")
                        createEngineObjectData(
                            schema.schema.getObjectType("Circle"),
                            mapOf("area" to 314)
                        )
                    }
                }
            }
            field("Query" to "gallery") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Gallery"),
                            mapOf(
                                "title" to "My Gallery",
                                "shapes" to listOf(
                                    ctx.createRootFieldReference(
                                        rootFieldPath = listOf("shapeFactory", "create"),
                                        type = schema.schema.getType("Shape") as GraphQLCompositeType,
                                        args = mapOf("shouldFail" to false),
                                    ),
                                    ctx.createRootFieldReference(
                                        rootFieldPath = listOf("shapeFactory", "create"),
                                        type = schema.schema.getType("Shape") as GraphQLCompositeType,
                                        args = mapOf("shouldFail" to true),
                                    ),
                                )
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ gallery { title shapes { area } } }")
            val data = result.getData<Map<String, Any?>>()
            val gallery = data["gallery"] as Map<*, *>
            assertEquals("My Gallery", gallery["title"])
            val shapes = gallery["shapes"] as List<*>
            assertEquals(2, shapes.size)
            assertEquals(mapOf("area" to 314), shapes[0])
            assertEquals(null, shapes[1])
            assertEquals(1, result.errors.size) { "Expected exactly one GraphQL error, got ${result.errors.size}: ${result.errors.map { "path=${it.path} msg=${it.message}" }}" }
            assertEquals(listOf("gallery", "shapes", 1), result.errors[0].path)
            assertTrue(result.errors[0].message.contains("factory failed"))
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
    fun `abstract-typed factory function error propagation`() {
        MockTenantModuleBootstrapper(
            """
            interface Vehicle {
                speed: Int
            }
            type Car implements Vehicle {
                speed: Int
                doors: Int
            }
            type VehicleFactory @namespaceType {
                create: Vehicle @resolver
            }
            extend type Query {
                vehicleFactory: VehicleFactory
                vehicle: Vehicle @resolver
            }
        """
        ) {
            field("VehicleFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        throw RuntimeException("abstract factory failed")
                    }
                }
            }
            field("Query" to "vehicle") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        ctx.createRootFieldReference(
                            rootFieldPath = listOf("vehicleFactory", "create"),
                            type = schema.schema.getType("Vehicle") as GraphQLCompositeType,
                            args = emptyMap(),
                        )
                    }
                }
            }
        }.runFeatureTest {
            val result = runQuery("{ vehicle { speed ... on Car { doors } } }")
            assertEquals(mapOf("vehicle" to null), result.getData())
            assertTrue(result.errors.any { it.path == listOf("vehicle") })
        }
    }

    @Test
    fun `nested root field reference failure preserves sibling fields`() {
        MockTenantModuleBootstrapper(
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
        MockTenantModuleBootstrapper(
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
    fun `abstract root field reference nested inside resolver response`() {
        MockTenantModuleBootstrapper(
            """
            interface Shape {
                area: Int
            }
            type Circle implements Shape {
                area: Int
                radius: Int
            }
            type ShapeFactory @namespaceType {
                create: Shape @resolver
            }
            type Canvas {
                label: String
                shape: Shape
            }
            extend type Query {
                shapeFactory: ShapeFactory
                canvas: Canvas @resolver
            }
        """
        ) {
            field("ShapeFactory" to "create") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Circle"),
                            mapOf("area" to 314, "radius" to 10)
                        )
                    }
                }
            }
            field("Query" to "canvas") {
                resolver {
                    fn { _, _, _, _, ctx ->
                        createEngineObjectData(
                            schema.schema.getObjectType("Canvas"),
                            mapOf(
                                "label" to "My Canvas",
                                "shape" to ctx.createRootFieldReference(
                                    rootFieldPath = listOf("shapeFactory", "create"),
                                    type = schema.schema.getType("Shape") as GraphQLCompositeType,
                                    args = emptyMap(),
                                )
                            )
                        )
                    }
                }
            }
        }.runFeatureTest {
            runQuery("{ canvas { label shape { area ... on Circle { radius } } } }")
                .assertJson("""{"data": {"canvas": {"label": "My Canvas", "shape": {"area": 314, "radius": 10}}}}""")
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
