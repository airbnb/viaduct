package viaduct.api.testing

import viaduct.api.FieldValue
import viaduct.api.ResolverBase
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.VisibleForTest

/**
 * Type-safe tester for field resolvers.
 *
 * This tester provides strong typing for all resolver inputs and outputs, eliminating
 * the need for reflection-based type inference. All type parameters must be specified
 * explicitly when creating the tester.
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
 * class WishlistNameResolverTest {
 *     private val tester = FieldResolverTester.create<
 *         Wishlist,                    // O: Object type
 *         Query,                       // Q: Query type
 *         Wishlist_Name_Arguments,     // A: Arguments type
 *         String                       // R: Return type
 *     >(
 *         ResolverTester.TesterConfig(
 *             schemaSDL = MY_SCHEMA_SDL
 *         )
 *     )
 *
 *     @Test
 *     fun testNameResolver() = runBlocking {
 *         val wishlist = Wishlist.Builder(tester.context)
 *             .internalName("Hawaii Trip")
 *             .build()
 *
 *         val result = tester.test(Wishlist_NameResolver()) {
 *             objectValue = wishlist
 *             arguments = Wishlist_Name_Arguments()
 *         }
 *
 *         assertEquals("Hawaii Trip", result)
 *     }
 * }
 * ```
 *
 * ## Type Parameters
 *
 * - **O**: The Object type that this field belongs to (e.g., `Wishlist`, `User`)
 * - **Q**: The Query type (usually just `Query`)
 * - **A**: The Arguments type for this field (e.g., `Wishlist_Name_Arguments`)
 * - **R**: The return/selection set type of the field (e.g., `String`, `User`, `List<Item>`)
 *
 * @since 1.0
 */
@VisibleForTest
interface FieldResolverTester<O : Object, Q : Query, A : Arguments, R : CompositeOutput> : ResolverTester {
    /**
     * Test a field resolver with the provided configuration.
     *
     * This method calls the resolver's `resolve` method with a properly typed context
     * constructed from the configuration block.
     *
     * @param resolver The resolver instance to test
     * @param block Configuration block for test parameters
     * @return The result of resolver.resolve()
     *
     * Example:
     * ```kotlin
     * val result = tester.test(myResolver) {
     *     objectValue = myObject         // required
     *     arguments = myArgs             // required (use Arguments.NoArguments if field has no args)
     *     queryValue = myQuery           // optional
     *     selections = mySelections      // optional
     *     contextQueryValues = listOf()  // optional
     * }
     * ```
     */
    suspend fun test(
        resolver: ResolverBase<R>,
        block: FieldTestConfig<O, Q, A, R>.() -> Unit
    ): R

    /**
     * Test a batch field resolver with the provided configuration.
     *
     * This method calls the resolver's `batchResolve` method with properly typed contexts
     * for each object in the batch.
     *
     * **Note:** Batch resolvers do not support per-item arguments. All items in the batch
     * share the same arguments (typically `Arguments.NoArguments`).
     *
     * @param resolver The resolver instance to test (must implement batch resolution)
     * @param block Configuration block for batch test parameters
     * @return List of FieldValue results from resolver.batchResolve()
     *
     * Example:
     * ```kotlin
     * val results = tester.testBatch(myBatchResolver) {
     *     objectValues = listOf(obj1, obj2, obj3)  // required
     *     queryValues = listOf(q1, q2, q3)         // optional, defaults to empty queries
     *     selections = mySelections                 // optional
     * }
     * ```
     */
    suspend fun testBatch(
        resolver: ResolverBase<R>,
        block: BatchFieldTestConfig<O, Q, A, R>.() -> Unit
    ): List<FieldValue<R>>

    /**
     * Configuration for field resolver tests.
     *
     * ## Required Properties
     * - **objectValue**: The object containing the field being resolved (required)
     * - **arguments**: The field's input arguments (required - use `Arguments.NoArguments` if none)
     *
     * ## Optional Properties
     * - **queryValue**: Query-level data accessible via `ctx.queryValue`
     * - **requestContext**: Request-level context object
     * - **selections**: The selection set for the field's return type
     * - **contextQueryValues**: Query objects for `ctx.query()` calls
     */
    class FieldTestConfig<O : Object, Q : Query, A : Arguments, R : CompositeOutput> {
        /**
         * The object containing the field being resolved.
         * This is **required** and must be set before calling `test()`.
         *
         * Example:
         * ```kotlin
         * objectValue = Wishlist.Builder(tester.context)
         *     .internalName("My List")
         *     .build()
         * ```
         */
        var objectValue: O? = null

        /**
         * Query-level data accessible via `ctx.queryValue`.
         * Optional - defaults to an empty query placeholder if not set.
         */
        var queryValue: Q? = null

        /**
         * Field arguments. This is **required** and must be set before calling `test()`.
         * Use `Arguments.NoArguments` for fields that don't take arguments.
         *
         * Example:
         * ```kotlin
         * // For fields with arguments:
         * arguments = Wishlist_Name_Arguments(format = "uppercase")
         *
         * // For fields without arguments:
         * arguments = Arguments.NoArguments
         * ```
         */
        var arguments: A? = null

        /** Optional request context passed to the resolver */
        var requestContext: Any? = null

        /**
         * Selection set for the field's return type.
         * Defaults to [SelectionSet.NoSelections] which indicates no specific fields are selected.
         */
        var selections: SelectionSet<R>? = null

        /** Query objects for `ctx.query()` calls */
        var contextQueryValues: List<Query> = emptyList()
    }

    /**
     * Configuration for batch field resolver tests.
     *
     * ## Required Properties
     * - **objectValues**: List of objects for batch resolution (required, must be non-empty)
     *
     * ## Optional Properties
     * - **queryValues**: List of query values (one per object), defaults to empty queries
     * - **requestContext**: Request-level context object
     * - **selections**: The selection set for all items in the batch
     * - **contextQueryValues**: Query objects for `ctx.query()` calls
     *
     * **Note:** Batch resolvers do not support per-item arguments.
     */
    class BatchFieldTestConfig<O : Object, Q : Query, A : Arguments, R : CompositeOutput> {
        /**
         * List of objects for batch resolution.
         * This is **required** and must contain at least one element.
         */
        var objectValues: List<O> = emptyList()

        /**
         * List of query values (one per object).
         * If not provided or empty, defaults to empty query placeholders for each object.
         * If provided, must have the same size as [objectValues].
         */
        var queryValues: List<Q> = emptyList()

        /** Optional request context passed to all resolvers in the batch */
        var requestContext: Any? = null

        /**
         * Selection set for all items in the batch.
         * Defaults to [SelectionSet.NoSelections] if not set.
         */
        var selections: SelectionSet<R>? = null

        /** Query objects for `ctx.query()` calls */
        var contextQueryValues: List<Query> = emptyList()
    }

    companion object {
        /**
         * Create a field resolver tester with explicit type parameters.
         *
         * This factory method creates a tester that validates all inputs at compile-time
         * using the specified type parameters. No reflection is needed to infer types.
         *
         * ## Type Parameters
         * All four type parameters must be specified explicitly:
         * - **O**: Object type (e.g., `Wishlist`)
         * - **Q**: Query type (usually just `Query`)
         * - **A**: Arguments type (e.g., `Wishlist_Name_Arguments` or `Arguments.NoArguments`)
         * - **R**: Return type (e.g., `String`, `User`, etc.)
         *
         * ## Example
         * ```kotlin
         * val tester = FieldResolverTester.create<
         *     Wishlist,                   // Object type
         *     Query,                      // Query type
         *     Wishlist_Name_Arguments,    // Arguments type
         *     String                      // Return type
         * >(
         *     ResolverTester.TesterConfig(schemaSDL = schemaSDL)
         * )
         * ```
         *
         * @param config Configuration specifying schema and GRT package
         * @return A type-safe field resolver tester
         */
        fun <O : Object, Q : Query, A : Arguments, R : CompositeOutput> create(config: ResolverTester.TesterConfig): FieldResolverTester<O, Q, A, R> = FieldResolverTesterImpl(config)
    }
}

/**
 * Internal placeholder query for optional field test configuration.
 *
 * This sentinel object is used when `queryValue` is not explicitly set in test configuration.
 * It represents a minimal/empty query that satisfies the type system while indicating
 * that no specific query data was provided.
 */
internal object NullQuery : Query
