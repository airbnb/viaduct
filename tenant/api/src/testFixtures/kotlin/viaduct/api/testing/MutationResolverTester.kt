package viaduct.api.testing

import viaduct.api.internal.ResolverBase
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.Query
import viaduct.apiannotations.StableApi
import viaduct.apiannotations.TestingApi

/**
 * Type-safe tester for mutation field resolvers.
 *
 * This tester provides strong typing for mutation resolver inputs and outputs.
 * Unlike [FieldResolverTester], mutation testers don't have an object type parameter
 * since mutations operate at the root level.
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
 * class CreateWishlistMutationTest {
 *     private val tester = MutationResolverTester.create<
 *         Query,                              // Q: Query type
 *         Mutation_CreateWishlist_Arguments,  // A: Arguments type
 *         CreateWishlistPayload               // O: Output type
 *     >(
 *         ResolverTester.TesterConfig(
 *             schemaSDL = MY_SCHEMA_SDL
 *         )
 *     )
 *
 *     @Test
 *     fun testCreateWishlist() = runBlocking {
 *         val result = tester.test(Mutation_CreateWishlistResolver()) {
 *             arguments = Mutation_CreateWishlist_Arguments(
 *                 input = CreateWishlistInput(name = "My List")
 *             )
 *         }
 *
 *         assertNotNull(result.wishlist)
 *         assertEquals("My List", result.wishlist.name)
 *     }
 * }
 * ```
 *
 * ## Type Parameters
 *
 * - **Q**: The Query type (usually just `Query`)
 * - **A**: The Arguments type for this mutation (e.g., `Mutation_CreateWishlist_Arguments`)
 * - **O**: The output/return type of the mutation (e.g., `CreateWishlistPayload`)
 *
 * @since 1.0
 */
@StableApi
@TestingApi
interface MutationResolverTester<Q : Query, A : Arguments, O : CompositeOutput> : ResolverTester {
    /**
     * Test a mutation resolver with the provided configuration.
     *
     * This method calls the resolver's `resolve` method with a properly typed context
     * constructed from the configuration block.
     *
     * @param resolver The mutation resolver instance to test
     * @param block Configuration block for test parameters
     * @return The result of resolver.resolve()
     *
     * Example:
     * ```kotlin
     * val result = tester.test(myMutationResolver) {
     *     arguments = myMutationArgs        // required
     *     queryValue = myQuery              // optional
     *     selections = mySelections         // optional
     *     contextQueryValues = listOf()     // optional
     *     contextMutationValues = listOf()  // optional
     * }
     * ```
     */
    suspend fun test(
        resolver: ResolverBase<O>,
        block: MutationTestConfig<Q, A, O>.() -> Unit
    ): O

    /**
     * Configuration for mutation resolver tests.
     *
     * ## Required Properties
     * - **arguments**: The mutation's input arguments (required)
     *
     * ## Optional Properties
     * - **queryValue**: Query-level data accessible via `ctx.queryValue`
     * - **requestContext**: Request-level context object
     * - **selections**: The selection set for the mutation result
     * - **contextQueryValues**: Query objects for `ctx.query()` calls
     * - **contextMutationValues**: Mutation objects for `ctx.mutation()` calls
     */
    class MutationTestConfig<Q : Query, A : Arguments, O : CompositeOutput> {
        /**
         * Query-level data accessible via `ctx.queryValue`.
         * Optional - defaults to an empty query placeholder if not set.
         */
        var queryValue: Q? = null

        /**
         * Mutation arguments. This is **required** and must be set before calling `test()`.
         *
         * Example:
         * ```kotlin
         * arguments = Mutation_CreateWishlist_Arguments(
         *     input = CreateWishlistInput(name = "My List")
         * )
         * ```
         */
        var arguments: A? = null

        /** Optional request context passed to the resolver */
        var requestContext: Any? = null

        /**
         * Selection set for the mutation's return type.
         * Defaults to [SelectionSet.NoSelections] which indicates no specific fields are selected.
         */
        var selections: SelectionSet<O>? = null

        /** Query objects for `ctx.query()` calls */
        var contextQueryValues: List<Query> = emptyList()

        /** Mutation objects for `ctx.mutation()` calls */
        var contextMutationValues: List<Mutation> = emptyList()
    }

    companion object {
        /**
         * Create a mutation resolver tester with explicit type parameters.
         *
         * This factory method creates a tester that validates all inputs at compile-time
         * using the specified type parameters.
         *
         * ## Type Parameters
         * All three type parameters must be specified explicitly:
         * - **Q**: Query type (usually just `Query`)
         * - **A**: Arguments type (e.g., `Mutation_CreateWishlist_Arguments`)
         * - **O**: Output type (e.g., `CreateWishlistPayload`)
         *
         * ## Example
         * ```kotlin
         * val tester = MutationResolverTester.create<
         *     Query,                              // Query type
         *     Mutation_CreateWishlist_Arguments,  // Arguments type
         *     CreateWishlistPayload               // Output type
         * >(
         *     ResolverTester.TesterConfig(schemaSDL = schemaSDL)
         * )
         * ```
         *
         * @param config Configuration specifying schema and GRT package
         * @return A type-safe mutation resolver tester
         */
        fun <Q : Query, A : Arguments, O : CompositeOutput> create(config: ResolverTester.TesterConfig): MutationResolverTester<Q, A, O> = MutationResolverTesterImpl(config)
    }
}
