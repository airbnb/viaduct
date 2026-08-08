@file:Suppress("unused", "ClassName")
@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.tutorial08

import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.jupiter.api.Assertions.assertEquals as kotlinAssertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.api.FieldValue
import viaduct.api.batch.batchByOwnFields
import viaduct.api.resolver.Resolver
import viaduct.apiannotations.ExperimentalApi
import viaduct.graphql.test.assertEquals
import viaduct.tenant.tutorial08.resolverbases.NodeResolvers
import viaduct.tenant.tutorial08.resolverbases.QueryResolvers

/**
 * LEARNING OBJECTIVES:
 * - Apply batching to Node Resolver operations
 * - Optimize multiple object lookups by GlobalID
 * - Handle mixed valid/invalid IDs in batch operations
 * - Combine Node Resolvers with batch optimization
 * - Partition heterogeneous selective batches by requested own fields
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - Selective Batch Node Resolvers with batchResolve() method
 * - batchByOwnFields() selection-aware grouping
 * - FieldValue error handling for individual failures
 * - ctx.nodeRef() automatic batching
 * - Multiple node requests in single GraphQL query
 *
 * CONCEPTS COVERED:
 * - N+1 problem at object level (multiple node lookups)
 * - Batch object creation from multiple GlobalIDs
 * - Error isolation in batch operations
 *
 * PREVIOUS: [viaduct.tenant.tutorial07.SimpleBatchResolverFeatureAppTest]
 * NEXT: [viaduct.tenant.tutorial09.VariablesDirectivesFeatureAppTest]
 *
 * ## Schema
 *
 * ```graphql
 * type Product implements Node @resolver(isBatching: true, isSelective: true) {
 *   id: ID!
 *   name: String!
 *   price: Float!
 *   category: String!
 * }
 *
 * extend type Query {
 *   products(ids: [String!]!): [Product!]! @resolver
 *   product(id: String!): Product! @resolver
 * }
 * ```
 */
class BatchNodeResolverFeatureAppTest : BatchNodeResolverContractTest() {
    companion object {
        // PERFORMANCE TRACKING
        val backendBatchSizes = ConcurrentLinkedQueue<Int>()
    }

    @BeforeEach
    fun setUp() {
        backendBatchSizes.clear()
    }

    /**
     * BATCH NODE RESOLVER - Optimizes multiple object creation
     *
     * What YOU write:
     * - Implement batchResolve() for multiple GlobalIDs at once
     * - Group contexts by selection differences that affect backend work
     * - Extract all internal IDs from GlobalIDs
     * - Make one database call for each compatible selection group
     * - Return Map<Context, FieldValue<T>> with proper error handling
     *
     * What VIADUCT handles:
     * - Collects all ctx.nodeRef() calls requesting same object type
     * - Routes to batchResolve() instead of individual resolve() calls
     * - Exposes each context's requested selections
     * - Maps results back to individual node requests
     * - Handles per-object error cases
     */
    @Resolver
    class ProductNodeResolver : NodeResolvers.Product() {
        override suspend fun batchResolve(contexts: List<Context>): Map<Context, FieldValue<Product>> =
            batchByOwnFields(contexts) { group ->
                // EXTRACT ALL INTERNAL IDS from GlobalIDs
                val productIds = group.contexts.map { ctx -> ctx.id.internalID }

                // PERFORMANCE TRACKING
                backendBatchSizes.add(productIds.size)

                // ONE DATABASE QUERY PER OWN-FIELD GROUP - instead of N separate queries
                // In reality: SELECT * FROM products WHERE id IN (?, ?, ?)
                val productsData = fetchProductsByIds(productIds)

                // RETURN RESULTS with individual error handling
                group.contexts.associateWith { ctx ->
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
                ctx.nodeRef(ctx.globalIDFor(Product.Reflection, id))
            }
        }
    }

    @Resolver
    class ProductResolver : QueryResolvers.Product() { // Generated from query field
        override suspend fun resolve(ctx: Context): Product {
            return ctx.nodeRef(ctx.globalIDFor(Product.Reflection, ctx.arguments.id))
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
        kotlinAssertEquals(1, backendBatchSizes.size, "Expected exactly 1 backend batch")
        kotlinAssertEquals(3, backendBatchSizes.first(), "Expected batch size of 3 products")
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
        kotlinAssertEquals(1, backendBatchSizes.size, "Expected exactly 1 backend batch")
        kotlinAssertEquals(1, backendBatchSizes.first(), "Expected batch size of 1 product")
    }

    @Test
    fun `Multiple selection shapes are grouped by own fields`() {
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

        // product1 and moreProducts share {name, price}; product2 requests {name, category}.
        kotlinAssertEquals(2, backendBatchSizes.size, "Expected one backend batch per own-field set")
        kotlinAssertEquals(listOf(1, 3), backendBatchSizes.sorted())
    }

    /**
     * EXECUTION FLOW WITH BATCH NODE RESOLVERS:
     *
     * Query: products(ids: ["laptop-123", "phone-456"])
     *
     * 1. productsResolver.resolve() called
     * 2. For each ID: ctx.nodeRef(globalIDFor(Product.Reflection, id))
     * 3. Viaduct collects all Product node requests
     * 4. Single ProductNodeResolver.batchResolve() call with all contexts
     * 5. batchByOwnFields() partitions contexts by directly selected fields
     * 6. Extract IDs and issue one database query for each selection group
     * 7. Build Product objects and return them keyed by their original contexts
     * 8. The utility combines the maps without replacing the original context keys
     * 9. Viaduct maps results back to individual requests by context identity
     *
     * KEY TAKEAWAYS:
     * - Batch Node Resolvers optimize multiple object creation
     * - Use when multiple ctx.nodeRef() calls request same type
     * - Selection-aware groups preserve batching without underfetching
     * - Omit missing nodes; use FieldValue.ofError() for other individual failures
     * - Automatic batching works across different query fields
     * - Significant performance improvement for object-heavy queries
     */
}
