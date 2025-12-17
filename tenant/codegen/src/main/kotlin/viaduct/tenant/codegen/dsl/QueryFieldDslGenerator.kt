/**
 * Query Field DSL Generator.
 *
 * Generates specialized builder classes for query fields that combine:
 * - Input argument setters as DSL functions
 * - Selection set fields from the return type
 *
 * This is a convenience wrapper around [operationFieldDslGen] for queries.
 *
 * ## Example
 *
 * For a query like:
 * ```graphql
 * type Query {
 *     searchCharacter(search: CharacterSearchInput!): [Character]
 * }
 * ```
 *
 * Generates a builder that allows:
 * ```kotlin
 * query {
 *     searchCharacter {
 *         search {
 *             name = "Luke"
 *         }
 *         id
 *         name
 *     }
 * }
 * ```
 *
 * @see QueryDslGenerator for the main query DSL
 * @see InputDslGenerator for input type builders
 */
package viaduct.tenant.codegen.dsl

import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper

// =============================================================================
// Public API
// =============================================================================

/**
 * Generates a specialized query field builder that combines input arguments
 * with selection set fields.
 *
 * @param pkg The target package name for the generated DSL classes
 * @param queryField The query field definition
 * @param returnType The return type definition (Object, Interface, or Union)
 * @param baseTypeMapper The type mapper for GraphQL to Kotlin type conversion
 * @return STContents ready to be written to a file
 */
fun queryFieldDslGen(
    pkg: String,
    queryField: ViaductSchema.Field,
    returnType: ViaductSchema.TypeDef,
    baseTypeMapper: BaseTypeMapper
): STContents = operationFieldDslGen(pkg, queryField, returnType, OperationType.QUERY, baseTypeMapper)

/**
 * Generates the builder class name for a query field.
 */
fun getQueryFieldBuilderName(fieldName: String): String =
    getOperationFieldBuilderName(fieldName, OperationType.QUERY)
