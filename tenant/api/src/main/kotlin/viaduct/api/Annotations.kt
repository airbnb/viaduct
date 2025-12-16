package viaduct.api

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
 *     return ctx.objectValue.getFieldName()
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
 *     val obj = ctx.objectValue
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
 *       val character = ctx.objectValue
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

/**
 * @property name the name of the variable being defined
 * @property fromObjectField a path into a selection set of the field to which this annotation is applied.
 *
 *  For example, given a [Resolver] annotation with this `objectValueFragment`:
 *  ```graphql
 *  {
 *    foo(x: 2) {
 *       myBar: bar {
 *         y
 *       }
 *    }
 *  }
 *  ```
 *  Then a "foo.myBar.y" [fromObjectField] value will bind the value of the "y" selection to a variable
 *  named [name].
 *
 *  The value of [fromObjectField] is subject to these requirements:
 *
 *  1. the path must be a path that is selected in the selection set this [Variable] is bound to
 *  1. the path must terminate on a scalar or enum value, or a potentially-nested
 *     list that wraps one of these values
 *  1. the path may not traverse through list-valued fields
 *  1. the type at the end of the path must be compatible with the location that this variable is used.
 *     For example, if the variable location is non-nullable and has no default value, then the path may
 *     not traverse through nullable selections, or selections with variable @skip/@include conditions,
 *     or fragments with narrowing type conditions
 *
 * @property fromQueryField see [fromObjectField]
 *
 * @property fromArgument a path into a GraphQL argument of the field to which this annotation
 *  is applied.
 *
 *  Example:
 *  ```
 *  name = "x"
 *  fromArgument = "foo"
 *  ```
 *  Will bind the value from the "foo" argument of the dependent field to a variable named "x"
 *
 *  The value of [fromArgument] may be a dot-separated path, in which case the path will be
 *  traversed to select a value to bind to [name].
 *  This path may not traverse through lists, though it may terminate on a list or input
 *  object type.
 *  If any value in the traversal path is null, then the final extracted value will be null.
 *  If a supplied value for a traversal step is missing but a default value is defined for the
 *  argument or input field, then the default value will be used.
 *
 *  Example:
 *  ```
 *  name = "x"
 *  fromArgument = "foo.bar"
 *  ```
 *  Will traverse through the "foo" field of the dependent field, which must be an input type, and
 *  will bind the value of the "bar" input field to a variable named "x".
 *
 *  In all cases, the schema type of the argument indicated by this property must be coercible to
 *  the type in which this variable is used.
 */
@StableApi
annotation class Variable(
    val name: String,
    val fromObjectField: String = UNSET_STRING_VALUE,
    val fromQueryField: String = UNSET_STRING_VALUE,
    val fromArgument: String = UNSET_STRING_VALUE
) {
    companion object {
        const val UNSET_STRING_VALUE = "XY!#* N0T S3T!"
    }
}

@StableApi
@Target(AnnotationTarget.CLASS)
annotation class Variables(
    /**
     * A string describing the names and types of 1 or more variables.
     * Names and types are separated by `:`, and name-type pairs are joined by a colon.
     * All spaces are ignored.
     *
     * Example:
     * ```
     *   "foo: Int, bar: String!"
     * ```
     */
    val types: String
)
