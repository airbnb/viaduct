/**
 * Operation Field DSL Generator.
 *
 * Generates specialized builder classes for query/mutation fields that combine
 * input argument setters with selection set fields from the return type.
 *
 * @see QueryDslGenerator for the main query DSL
 * @see MutationDslGenerator for the main mutation DSL
 * @see InputDslGenerator for input type builders
 */
package viaduct.tenant.codegen.dsl

import getEscapedFieldName
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.kmType

/**
 * Type of GraphQL operation (Query or Mutation).
 */
enum class OperationType(val suffix: String) {
    QUERY("QueryBuilder"),
    MUTATION("MutationBuilder")
}

// =============================================================================
// Public API
// =============================================================================

/**
 * Generates a specialized operation field builder that combines input arguments
 * with selection set fields.
 *
 * @param pkg The target package name for the generated DSL classes
 * @param field The operation field definition
 * @param returnType The return type definition (Object, Interface, or Union)
 * @param operationType The type of operation (Query or Mutation)
 * @param baseTypeMapper The type mapper for GraphQL to Kotlin type conversion
 * @return STContents ready to be written to a file
 */
fun operationFieldDslGen(
    pkg: String,
    field: ViaductSchema.Field,
    returnType: ViaductSchema.TypeDef,
    operationType: OperationType,
    baseTypeMapper: BaseTypeMapper
): STContents = STContents(
    OPERATION_FIELD_DSL_TEMPLATE,
    OperationFieldDslModelImpl(pkg, field, returnType, operationType, baseTypeMapper)
)

/**
 * Generates the builder class name for an operation field.
 */
fun getOperationFieldBuilderName(fieldName: String, operationType: OperationType): String =
    "${fieldName.replaceFirstChar { it.uppercase() }}${operationType.suffix}"

// =============================================================================
// Template Model Interface
// =============================================================================

private interface OperationFieldDslModel {
    val pkg: String
    val builderName: String
    val fieldName: String
    val returnTypeName: String
    val parentBuilderName: String
    val operationTypeLabel: String
    val inputArgs: List<OperationInputArgModel>
    val scalarArgs: List<OperationScalarArgModel>
}

// =============================================================================
// Argument Model Classes
// =============================================================================

/**
 * Model for input type arguments (generates a DSL function)
 */
class OperationInputArgModel(
    argDef: ViaductSchema.HasDefaultValue
) {
    val escapedName: String = getEscapedFieldName(argDef.name)
    val argName: String = argDef.name
    val graphqlType: String = argDef.type.toString()
    val isNullable: Boolean = argDef.type.isNullable

    private val inputTypeDef: ViaductSchema.Input = argDef.type.baseTypeDef as ViaductSchema.Input
    val inputBuilderType: String = "${inputTypeDef.name}Builder"
}

/**
 * Model for scalar/enum arguments (generates a property)
 */
class OperationScalarArgModel(
    argDef: ViaductSchema.HasDefaultValue,
    pkg: String,
    baseTypeMapper: BaseTypeMapper
) {
    val escapedName: String = getEscapedFieldName(argDef.name)
    val argName: String = argDef.name
    val graphqlType: String = argDef.type.toString()

    val kotlinType: String = run {
        argDef.kmType(JavaName(pkg).asKmName, baseTypeMapper)
            .kotlinTypeString
            .replaceGlobalIdWithString()
            .simplifyKotlinType()
    }
}

// =============================================================================
// Template Model Implementation
// =============================================================================

private class OperationFieldDslModelImpl(
    override val pkg: String,
    field: ViaductSchema.Field,
    returnType: ViaductSchema.TypeDef,
    operationType: OperationType,
    baseTypeMapper: BaseTypeMapper
) : OperationFieldDslModel {

    override val builderName: String = getOperationFieldBuilderName(field.name, operationType)
    override val fieldName: String = field.name
    override val returnTypeName: String = returnType.name
    override val parentBuilderName: String = "${returnType.name}DslBuilder"
    override val operationTypeLabel: String = operationType.name.lowercase()

    override val inputArgs: List<OperationInputArgModel>
    override val scalarArgs: List<OperationScalarArgModel>

    init {
        val inputs = mutableListOf<OperationInputArgModel>()
        val scalars = mutableListOf<OperationScalarArgModel>()

        for (arg in field.args) {
            val argType = arg.type.baseTypeDef
            if (argType is ViaductSchema.Input) {
                inputs.add(OperationInputArgModel(arg))
            } else {
                scalars.add(OperationScalarArgModel(arg, pkg, baseTypeMapper))
            }
        }

        inputArgs = inputs
        scalarArgs = scalars
    }
}

// =============================================================================
// StringTemplate Definition
// =============================================================================

private val OPERATION_FIELD_DSL_TEMPLATE = stTemplate(
    """
@file:Suppress("warnings")

package <mdl.pkg>

/**
 * DSL builder for the `<mdl.fieldName>` <mdl.operationTypeLabel> field.
 *
 * Combines argument setters with selection set fields from [<mdl.returnTypeName>].
 */
class <mdl.builderName> internal constructor() : <mdl.parentBuilderName>() {
    private val argValues = mutableMapOf\<String, Any?>()

<mdl.scalarArgs: { a |
    var <a.escapedName>: <a.kotlinType>
        get() = argValues["<a.argName>"] as <a.kotlinType>
        set(value) {
            argValues["<a.argName>"] = value
        \}
}; separator="\n">

<mdl.inputArgs: { a |
    fun <a.escapedName>(block: <a.inputBuilderType>.() -> Unit) {
        val inputBuilder = <a.inputBuilderType>()
        inputBuilder.block()
        argValues["<a.argName>"] = inputBuilder.build()
    \}
}; separator="\n">

    internal fun buildArgs(): Map\<String, Any?> = argValues.toMap()
}
"""
)
