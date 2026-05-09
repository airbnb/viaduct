package viaduct.api.resolver

import org.intellij.lang.annotations.Language
import viaduct.apiannotations.StableApi

/**
 * Annotation to mark a class as a resolver for a field or object in a GraphQL schema which
 * requires custom resolution logic rather than simple property access.
 *
 * Applied to fields that need runtime computation, database lookups, or complex business logic.
 *
 * The @resolver directive supports two types of fragment syntax for efficient field resolution:
 *
 * #### 1. Shorthand Fragment Syntax
 * ```kotlin
 * @Resolver("fieldName")
 * class MyFieldResolver {
 *   override suspend fun resolve(ctx: Context): String {
 *     // Automatically delegates to the specified field
 *     return ctx.getObjectValue().getFieldName()
 *   }
 * }
 * ```
 *
 * **Use cases**:
 * - Creating field aliases
 * - Simple field transformations
 * - Computed fields based on single existing fields
 *
 * #### 2. Full Fragment Syntax
 * ```kotlin
 * @Resolver(
 *   """
 *     fragment _ on MyType {
 *         field1
 *         field2
 *         field3
 *     }
 *     """
 * )
 * class MyComputedFieldResolver {
 *   override suspend fun resolve(ctx: Context): String {
 *     val obj = ctx.getObjectValue()
 *     // Can access all specified fields
 *     return "${obj.getField1()} - ${obj.getField2()} (${obj.getField3()})"
 *   }
 * }
 * ```
 *
 * **Use cases**:
 * - Computed fields requiring multiple source fields
 * - Performance optimization by specifying exact field requirements
 * - Complex business logic combining multiple attributes
 *
 * #### 3. Batch Resolver Fragment Syntax
 * ```kotlin
 * @Resolver(objectValueFragment = "fragment _ on Character { id name }")
 * class CharacterBatchResolver : CharacterResolvers.SomeField() {
 *   override suspend fun batchResolve(contexts: List<Context>): List<FieldValue<String>> {
 *     // Process multiple contexts efficiently in one batch
 *     return contexts.map { ctx ->
 *       val character = ctx.getObjectValue()
 *       FieldValue.ofValue("${character.getName()} processed in batch")
 *     }
 *   }
 * }
 * ```
 *
 * **Use cases**:
 * - Preventing N+1 query problems
 * - Efficient batch processing of multiple field requests
 * - Performance optimization for list queries
 *
 * **Example**:
 * ```graphql
 * type Character {
 *   name: String @resolver
 *   homeworld: Planet @resolver      # Standard resolver (or batch resolver)
 *   displayName: String @resolver    # Shorthand fragment: @Resolver("name")
 *   summary: String @resolver        # Full fragment: @Resolver("fragment _ on Character { name birthYear }")
 *   filmCount: Int @resolver         # Batch resolver for efficient counting
 *   richSummary: String @resolver    # Batch resolver with complex logic
 * }
 * ```
 *
 * ---
 *
 * @property[objectValueFragment] This property is a GraphQL fragment that describes the selection set
 * that must be fetched from the object type that contains the field being resolved.
 * This fragment may include arguments to fields, which will be passed to the data fetchers
 * of those fields.
 * @property[queryValueFragment] This property is a GraphQL fragment that describes the selection set
 * that must be fetched from the root query type.
 * This fragment may include arguments to fields, which will be passed to the data fetchers
 * of those fields.
 *
 * If either fragment is empty, then no additional selection set is required for that fragment.
 * @property[variables] This property is an array of [Variable] annotations that describe how to extract
 * values from the [graphql.schema.DataFetchingEnvironment] and bind them to variables used in the fragments.
 */
@StableApi
@Target(AnnotationTarget.CLASS)
annotation class Resolver(
    @Language("GraphQL") val objectValueFragment: String = "",
    @Language("GraphQL") val queryValueFragment: String = "",
    val variables: Array<Variable> = []
)
