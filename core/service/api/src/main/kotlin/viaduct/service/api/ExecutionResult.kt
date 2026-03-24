package viaduct.service.api

import viaduct.apiannotations.StableApi

/**
 * The result of executing a GraphQL operation through Viaduct.
 *
 * This is a Viaduct-specific wrapper that does not expose GraphQL Java types directly.
 * The structure of [getData] directly reflects the query provided in [ExecutionInput.operationText].
 *
 * ## Data and Errors Relationship
 *
 * - If [getData] returns `null`, [errors] will contain at least one error explaining why
 * - If [getData] returns data, [errors] may still contain errors for fields that failed (partial results)
 *
 * ## Partial Results (GraphQL Spec §6.4.4)
 *
 * GraphQL supports partial results: if a nullable field encounters an error, it returns `null`
 * but execution continues. The error is recorded in [errors] while other fields retain their data.
 * Only errors in non-nullable fields cause null to bubble up to parent fields.
 *
 * ## Example
 *
 * Given this query in [ExecutionInput.operationText]:
 * ```graphql
 * query {
 *   user(id: "123") {
 *     name
 *     posts { title }
 *   }
 * }
 * ```
 *
 * [getData] returns:
 * ```kotlin
 * mapOf(
 *   "user" to mapOf(
 *     "name" to "Alice",
 *     "posts" to listOf(mapOf("title" to "First Post"))
 *   )
 * )
 * ```
 */
@StableApi
interface ExecutionResult {
    /**
     * Returns the data from the GraphQL execution.
     *
     * The structure is `Map<String, Any?>` where keys are the top-level field names from the query.
     * Nested values follow this mapping:
     * - GraphQL Objects → `Map<String, Any?>`
     * - GraphQL Lists → `List<Any?>`
     * - GraphQL Scalars → String, Int, Boolean, Double, etc.
     * - GraphQL Nulls → `null`
     *
     * @return The execution data, or `null` if execution failed at the root level (see [errors])
     */
    fun getData(): Map<String, Any?>?

    /**
     * Errors that occurred during execution, sorted by path and then by message.
     *
     * This list is non-empty when:
     * - [getData] returns `null` (at least one error will explain why)
     * - Partial results occurred (nullable fields that encountered errors)
     * - Validation or parsing failed
     */
    val errors: List<GraphQLError>

    /**
     * Additional execution metadata as key-value pairs.
     */
    val extensions: Map<Any, Any?>?

    /**
     * Converts this result to the standard GraphQL specification format.
     * This produces a map suitable for JSON serialization in HTTP responses.
     */
    fun toSpecification(): Map<String, Any?>
}
