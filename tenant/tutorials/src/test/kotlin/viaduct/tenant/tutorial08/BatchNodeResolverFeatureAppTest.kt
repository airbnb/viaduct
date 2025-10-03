@file:Suppress("unused", "ClassName")

package viaduct.tenant.tutorial08

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals as kotlinAssertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.tutorial08.resolverbases.QueryResolvers

/**
 * LEARNING OBJECTIVES:
 * - Apply batching to Node Resolver operations
 * - Optimize multiple object lookups by GlobalID
 * - Handle mixed valid/invalid IDs in batch operations
 * - Combine Node Resolvers with batch optimization
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - Batch Node Resolvers with batchResolve() method
 * - FieldValue error handling for individual failures
 * - ctx.nodeFor() automatic batching
 * - Multiple node requests in single GraphQL query
 *
 * CONCEPTS COVERED:
 * - N+1 problem at object level (multiple node lookups)
 * - Batch object creation from multiple GlobalIDs
 * - Error isolation in batch operations
 *
 * PREVIOUS: [viaduct.tenant.tutorial07.SimpleBatchResolverFeatureAppTest]
 * NEXT: [viaduct.tenant.tutorial09.VariablesDirectivesFeatureAppTest]
 */
class BatchNodeResolverFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | type Product implements Node @resolver {
        |   id: ID!
        |   name: String!
        |   price: Float!
        |   category: String!
        | }
        |
        | extend type Query {
        |   products(ids: [String!]!): [Product!]! @resolver
        |   product(id: String!): Product! @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

    companion object {
        // PERFORMANCE TRACKING
        val batchResolveCalls = ConcurrentLinkedQueue<Int>()
    }

    @BeforeEach
    fun setUp() {
        batchResolveCalls.clear()
    }

    /**
     * BATCH NODE RESOLVER - Optimizes multiple object creation
     *
     * What YOU write:
     * - Implement batchResolve() for multiple GlobalIDs at once
     * - Extract all internal IDs from GlobalIDs
     * - Make single database call for all requested objects
     * - Return List<FieldValue<T>> with proper error handling
     *
     * What VIADUCT handles:
     * - Collects all ctx.nodeFor() calls requesting same object type
     * - Routes to batchResolve() instead of individual resolve() calls
     * - Maps results back to individual node requests
     * - Handles per-object error cases
     */
    class ProductNodeResolver : NodeResolvers.Product() { // Generated from "type Product implements Node @resolver"
        override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<Product>> {
            // EXTRACT ALL INTERNAL IDS from GlobalIDs
            val productIds = contexts.map { ctx -> ctx.id.internalID }

            // PERFORMANCE TRACKING
            batchResolveCalls.add(productIds.size)

            // SINGLE DATABASE QUERY - instead of N separate queries
            // In reality: SELECT * FROM products WHERE id IN (?, ?, ?)
            val productsData = fetchProductsByIds(productIds)

            // RETURN RESULTS with individual error handling
            return contexts.map { ctx ->
                val productId = ctx.id.internalID
                val productData = productsData[productId]

                if (productData != null) {
                    val product = Product.Builder(ctx)
                        .id(ctx.id)
                        .name(productData.name)
                        .price(productData.price)
                        .category(productData.category)
                        .build()
                    FieldValue.ofValue(product)
                } else {
                    // Individual error - doesn't fail entire batch
                    FieldValue.ofError(IllegalArgumentException("Product not found: $productId"))
                }
            }
        }

        // MOCK DATABASE - simulates single optimized query
        private fun fetchProductsByIds(productIds: List<String>): Map<String, ProductData> {
            val allProducts = mapOf(
                "laptop-123" to ProductData("Gaming Laptop", 1299.99, "Electronics"),
                "phone-456" to ProductData("Smartphone", 699.99, "Electronics"),
                "book-789" to ProductData("Kotlin Programming", 49.99, "Books"),
                "chair-101" to ProductData("Office Chair", 299.99, "Furniture"),
                "mouse-202" to ProductData("Wireless Mouse", 29.99, "Electronics")
            )

            // Filter to only requested products (WHERE id IN clause)
            return allProducts.filter { it.key in productIds }
        }

        private data class ProductData(
            val name: String,
            val price: Double,
            val category: String
        )
    }

    /**
     * QUERY RESOLVER - Triggers batch node resolution
     */
    @Resolver
    class productsResolver : QueryResolvers.Products() { // Generated from query field
        override suspend fun resolve(ctx: Context): List<Product> {
            // MULTIPLE NODE REQUESTS - automatically batched by Viaduct
            return ctx.arguments.ids.map { id ->
                ctx.nodeFor(ctx.globalIDFor(Product.Reflection, id))
            }
        }
    }

    @Resolver
    class ProductResolver : QueryResolvers.Product() { // Generated from query field
        override suspend fun resolve(ctx: Context): Product {
            return ctx.nodeFor(ctx.globalIDFor(Product.Reflection, ctx.arguments.id))
        }
    }

    @Test
    fun `Batch node resolver efficiently loads multiple products with single database call`() {
        execute(
            query = """
                query {
                    products(ids: ["laptop-123", "phone-456", "book-789"]) {
                        id
                        name
                        price
                        category
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                // NOTE: createGlobalIdString is a TEST-ONLY utility method provided by FeatureAppTestBase
                "products" to arrayOf(
                    {
                        "id" to createGlobalIdString(Product.Reflection, "laptop-123")
                        "name" to "Gaming Laptop"
                        "price" to 1299.99
                        "category" to "Electronics"
                    },
                    {
                        "id" to createGlobalIdString(Product.Reflection, "phone-456")
                        "name" to "Smartphone"
                        "price" to 699.99
                        "category" to "Electronics"
                    },
                    {
                        "id" to createGlobalIdString(Product.Reflection, "book-789")
                        "name" to "Kotlin Programming"
                        "price" to 49.99
                        "category" to "Books"
                    }
                )
            }
        }

        // EFFICIENCY PROOF - all 3 products in 1 batch call
        kotlinAssertEquals(1, batchResolveCalls.size, "Expected exactly 1 batchResolve call")
        kotlinAssertEquals(3, batchResolveCalls.first(), "Expected batch size of 3 products")
    }

    @Test
    fun `Batch resolver works with single product`() {
        // Even single product requests use batch resolver (batch size = 1)
        execute(
            query = """
                query {
                    product(id: "chair-101") {
                        name
                        price
                        category
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "product" to {
                    "name" to "Office Chair"
                    "price" to 299.99
                    "category" to "Furniture"
                }
            }
        }

        // Assert: single product still uses batch resolver (batch size 1)
        kotlinAssertEquals(1, batchResolveCalls.size, "Expected exactly 1 batchResolve call")
        kotlinAssertEquals(1, batchResolveCalls.first(), "Expected batch size of 1 product")
    }

    @Test
    fun `Multiple queries get batched together for maximum efficiency`() {
        execute(
            query = """
                query {
                    product1: product(id: "laptop-123") {
                        name
                        price
                    }
                    product2: product(id: "phone-456") {
                        name
                        category
                    }
                    moreProducts: products(ids: ["book-789", "mouse-202"]) {
                        name
                        price
                    }
                }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "product1" to {
                    "name" to "Gaming Laptop"
                    "price" to 1299.99
                }
                "product2" to {
                    "name" to "Smartphone"
                    "category" to "Electronics"
                }
                "moreProducts" to arrayOf(
                    {
                        "name" to "Kotlin Programming"
                        "price" to 49.99
                    },
                    {
                        "name" to "Wireless Mouse"
                        "price" to 29.99
                    }
                )
            }
        }

        // MAXIMUM BATCHING: 2 individual + 2 from array = 4 total in 1 call
        kotlinAssertEquals(1, batchResolveCalls.size, "Expected exactly 1 batchResolve call")
        kotlinAssertEquals(4, batchResolveCalls.first(), "Expected batch size of 4 products")
    }

    /**
     * EXECUTION FLOW WITH BATCH NODE RESOLVERS:
     *
     * Query: products(ids: ["laptop-123", "phone-456"])
     *
     * 1. productsResolver.resolve() called
     * 2. For each ID: ctx.nodeFor(globalIDFor(Product.Reflection, id))
     * 3. Viaduct collects all Product node requests
     * 4. Single ProductNodeResolver.batchResolve() call with all contexts
     * 5. Extract ["laptop-123", "phone-456"] from GlobalIDs
     * 6. Single database query for both products
     * 7. Build Product objects and return as FieldValue list
     * 8. Viaduct maps results back to individual requests
     *
     * KEY TAKEAWAYS:
     * - Batch Node Resolvers optimize multiple object creation
     * - Use when multiple ctx.nodeFor() calls request same type
     * - Single database call replaces N separate calls
     * - FieldValue.ofError() handles individual failures gracefully
     * - Automatic batching works across different query fields
     * - Significant performance improvement for object-heavy queries
     */
}
