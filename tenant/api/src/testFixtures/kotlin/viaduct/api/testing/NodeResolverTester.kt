package viaduct.api.testing

import viaduct.api.FieldValue
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.NodeResolverBase
import viaduct.api.select.SelectionSet
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.apiannotations.StableApi
import viaduct.apiannotations.TestingApi

/**
 * Type-safe tester for node resolvers.
 *
 * This tester provides strong typing for node resolver inputs and outputs.
 * Node resolvers resolve objects by their GlobalID and are used for the GraphQL
 * `node` interface pattern.
 *
 * ## Implementation Note
 *
 * While this API eliminates reflection-based **type inference** (all types are explicit),
 * the implementation still uses reflection for **method invocation** to call resolver
 * methods dynamically. This is appropriate for test fixtures and has no impact on
 * production code.
 *
 * ## Usage Example
 *
 * ```kotlin
 * class WishlistNodeResolverTest {
 *     private val tester = NodeResolverTester.create<Wishlist>(
 *         ResolverTester.TesterConfig(
 *             schemaSDL = MY_SCHEMA_SDL
 *         )
 *     )
 *
 *     @Test
 *     fun testNodeResolver() = runBlocking {
 *         val globalId = tester.globalIDFor(Wishlist.Reflection, "wishlist-123")
 *
 *         val result = tester.test(WishlistNodeResolver()) {
 *             id = globalId
 *         }
 *
 *         assertNotNull(result)
 *         assertEquals("wishlist-123", result.id)
 *     }
 * }
 * ```
 *
 * ## Type Parameter
 *
 * - **T**: The node type being resolved (must implement [NodeObject])
 *
 * @since 1.0
 */
@StableApi
@TestingApi
interface NodeResolverTester<T : NodeObject> : ResolverTester {
    /**
     * Test a node resolver with the provided configuration.
     *
     * This method calls the resolver's `resolve` method with a properly typed context
     * constructed from the configuration block.
     *
     * @param resolver The node resolver instance to test
     * @param block Configuration block for test parameters
     * @return The resolved node object
     *
     * Example:
     * ```kotlin
     * val result = tester.test(myNodeResolver) {
     *     id = globalId                      // required
     *     requestContext = myRequestContext  // optional
     *     selections = mySelections          // optional
     *     contextQueryValues = listOf()      // optional
     * }
     * ```
     */
    suspend fun test(
        resolver: NodeResolverBase<T>,
        block: NodeTestConfig<T>.() -> Unit
    ): T

    /**
     * Test a batch node resolver with the provided configuration.
     *
     * This method calls the resolver's `batchResolve` method with properly typed contexts
     * for each node ID in the batch.
     *
     * @param resolver The node resolver instance to test (must implement batch resolution)
     * @param block Configuration block for batch test parameters
     * @return List of FieldValue results from resolver.batchResolve()
     *
     * Example:
     * ```kotlin
     * val results = tester.testBatch(myBatchNodeResolver) {
     *     ids = listOf(id1, id2, id3)  // required
     *     selections = mySelections    // optional
     * }
     * ```
     */
    suspend fun testBatch(
        resolver: NodeResolverBase<T>,
        block: BatchNodeTestConfig<T>.() -> Unit
    ): List<FieldValue<T>>

    /**
     * Configuration for node resolver tests.
     *
     * ## Required Properties
     * - **id**: The GlobalID of the node to resolve (required)
     *
     * ## Optional Properties
     * - **requestContext**: Request-level context object
     * - **selections**: The selection set for the node
     * - **contextQueryValues**: Query objects for `ctx.query()` calls
     */
    class NodeTestConfig<T : NodeObject> {
        /**
         * The GlobalID of the node to resolve. This is **required**.
         *
         * Example:
         * ```kotlin
         * id = tester.globalIDFor(Wishlist.Reflection, "wishlist-123")
         * ```
         */
        lateinit var id: GlobalID<T>

        /** Optional request context passed to the resolver */
        var requestContext: Any? = null

        /**
         * Selection set for the node.
         * Defaults to [SelectionSet.NoSelections] if not set.
         */
        var selections: SelectionSet<T>? = null

        /** Query objects for `ctx.query()` calls */
        var contextQueryValues: List<Query> = emptyList()
    }

    /**
     * Configuration for batch node resolver tests.
     *
     * ## Required Properties
     * - **ids**: List of GlobalIDs to resolve in batch (required, must be non-empty)
     *
     * ## Optional Properties
     * - **requestContext**: Request-level context object
     * - **selections**: The selection set for all nodes in the batch
     * - **contextQueryValues**: Query objects for `ctx.query()` calls
     */
    class BatchNodeTestConfig<T : NodeObject> {
        /**
         * List of GlobalIDs to resolve. This is **required** and must be non-empty.
         *
         * Example:
         * ```kotlin
         * ids = listOf(
         *     tester.globalIDFor(Wishlist.Reflection, "id-1"),
         *     tester.globalIDFor(Wishlist.Reflection, "id-2")
         * )
         * ```
         */
        var ids: List<GlobalID<T>> = emptyList()

        /** Optional request context passed to all resolvers in the batch */
        var requestContext: Any? = null

        /**
         * Selection set for all nodes in the batch.
         * Defaults to [SelectionSet.NoSelections] if not set.
         */
        var selections: SelectionSet<T>? = null

        /** Query objects for `ctx.query()` calls */
        var contextQueryValues: List<Query> = emptyList()
    }

    companion object {
        /**
         * Create a node resolver tester with an explicit type parameter.
         *
         * This factory method creates a tester that validates all inputs at compile-time
         * using the specified type parameter.
         *
         * ## Type Parameter
         * - **T**: Node type (e.g., `Wishlist`, `User`) - must implement [NodeObject]
         *
         * ## Example
         * ```kotlin
         * val tester = NodeResolverTester.create<Wishlist>(
         *     ResolverTester.TesterConfig(schemaSDL = schemaSDL)
         * )
         * ```
         *
         * @param config Configuration specifying schema and GRT package
         * @return A type-safe node resolver tester
         */
        fun <T : NodeObject> create(config: ResolverTester.TesterConfig): NodeResolverTester<T> = NodeResolverTesterImpl(config)
    }
}
