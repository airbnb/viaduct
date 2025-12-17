/**
 * Mutation Field DSL Generator.
 *
 * Generates specialized builder classes for mutation fields that combine:
 * - Input argument setters as DSL functions
 * - Selection set fields from the return type
 *
 * This is a convenience wrapper around [operationFieldDslGen] for mutations.
 *
 * ## Example
 *
 * For a mutation like:
 * ```graphql
 * type Mutation {
 *     createCharacter(input: CreateCharacterInput!): Character
 * }
 * ```
 *
 * Generates a builder that allows:
 * ```kotlin
 * mutation {
 *     createCharacter {
 *         input {
 *             name = "Luke"
 *             age = 19
 *         }
 *         id
 *         name
 *     }
 * }
 * ```
 *
 * @see MutationDslGenerator for the main mutation DSL
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
 * Generates a specialized mutation field builder that combines input arguments
 * with selection set fields.
 *
 * @param pkg The target package name for the generated DSL classes
 * @param mutationField The mutation field definition
 * @param returnType The return type definition (Object, Interface, or Union)
 * @param baseTypeMapper The type mapper for GraphQL to Kotlin type conversion
 * @return STContents ready to be written to a file
 */
fun mutationFieldDslGen(
    pkg: String,
    mutationField: ViaductSchema.Field,
    returnType: ViaductSchema.TypeDef,
    baseTypeMapper: BaseTypeMapper
): STContents = operationFieldDslGen(pkg, mutationField, returnType, OperationType.MUTATION, baseTypeMapper)

/**
 * Generates the builder class name for a mutation field.
 */
fun getMutationFieldBuilderName(fieldName: String): String =
    getOperationFieldBuilderName(fieldName, OperationType.MUTATION)
