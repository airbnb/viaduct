/**
 * Mutation DSL Generator.
 *
 * Generates type-safe Kotlin DSL code for building GraphQL mutations.
 *
 * ## Key Difference from Query DSL
 *
 * Unlike queries where scalar fields without arguments are properties,
 * **all mutation fields are generated as functions**. This design choice
 * reflects that mutations are operations with side effects.
 *
 * Input types are passed as `Map<String, Any?>` for idiomatic Kotlin DSL usage.
 *
 * @see QueryDslGenerator for query generation
 * @see ObjectDslGenerator for nested object builder generation
 */
package viaduct.tenant.codegen.dsl

import getEscapedFieldName
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper

// =============================================================================
// Public API
// =============================================================================

/**
 * Generates the Mutation DSL code for a GraphQL Mutation type.
 *
 * @param pkg The target package name for the generated DSL classes
 * @param mutationDef The GraphQL Mutation object type definition
 * @param baseTypeMapper The type mapper for GraphQL to Kotlin type conversion
 * @return STContents ready to be written to a file
 */
fun mutationDslGen(
    pkg: String,
    mutationDef: ViaductSchema.Object,
    baseTypeMapper: BaseTypeMapper
): STContents = STContents(
    MUTATION_DSL_TEMPLATE,
    MutationDslModelImpl(pkg, mutationDef, baseTypeMapper)
)

// =============================================================================
// Template Model Interface
// =============================================================================

private interface MutationDslModel {
    val pkg: String
    val scalarFields: List<ScalarMutationFieldModel>
    val complexFields: List<ComplexMutationFieldModel>
}

// =============================================================================
// Field Model Classes
// =============================================================================

/**
 * Model for scalar mutation fields. All are generated as functions.
 */
private class ScalarMutationFieldModel(
    fieldDef: ViaductSchema.Field,
    pkg: String,
    baseTypeMapper: BaseTypeMapper
) {
    val escapedName: String = getEscapedFieldName(fieldDef.name)
    val fieldName: String = fieldDef.name
    val graphqlType: String = fieldDef.type.toString()

    val parameters: List<FieldParameterModel> = fieldDef.args.map {
        FieldParameterModel(it, pkg, baseTypeMapper)
    }

    val hasArgs: Boolean = fieldDef.args.isNotEmpty()
    val parameterSignature: String = buildParameterSignature(parameters, includeAlias = true)
    val parameterSerializers: String = buildParameterSerializers(parameters)
}

/**
 * Model for complex mutation fields that return objects/interfaces/unions.
 */
private class ComplexMutationFieldModel(
    fieldDef: ViaductSchema.Field,
    pkg: String,
    baseTypeMapper: BaseTypeMapper
) {
    val escapedName: String = getEscapedFieldName(fieldDef.name)
    val fieldName: String = fieldDef.name
    val graphqlType: String = fieldDef.type.toString()

    val parameters: List<FieldParameterModel> = fieldDef.args.map {
        FieldParameterModel(it, pkg, baseTypeMapper)
    }

    private val returnTypeDef: ViaductSchema.TypeDef = fieldDef.type.baseTypeDef

    // Check if this field has input type arguments - if so, use specialized builder
    val hasInputArgs: Boolean = fieldDef.args.any { it.type.baseTypeDef is ViaductSchema.Input }

    // Use specialized mutation builder if has input args, otherwise use standard selection builder
    val selectionBuilderType: String = if (hasInputArgs) {
        "${fieldDef.name.replaceFirstChar { it.uppercase() }}MutationBuilder"
    } else {
        "${returnTypeDef.name}DslBuilder"
    }

    val hasArgs: Boolean = fieldDef.args.isNotEmpty()

    // For fields with input args, we don't pass args as function parameters anymore
    val parameterSignature: String = if (hasInputArgs) {
        "alias: String? = null"
    } else {
        buildParameterSignature(parameters, includeAlias = true)
    }

    val parameterSerializers: String = buildParameterSerializers(parameters)

    // Only serialize args inline if NOT using input builder pattern
    val serializeArgsInline: Boolean = hasArgs && !hasInputArgs
}

// =============================================================================
// Template Model Implementation
// =============================================================================

private class MutationDslModelImpl(
    override val pkg: String,
    mutationDef: ViaductSchema.Object,
    baseTypeMapper: BaseTypeMapper
) : MutationDslModel {

    override val scalarFields: List<ScalarMutationFieldModel>
    override val complexFields: List<ComplexMutationFieldModel>

    init {
        val scalars = mutableListOf<ScalarMutationFieldModel>()
        val complex = mutableListOf<ComplexMutationFieldModel>()

        for (field in mutationDef.fields) {
            val returnType = field.type.baseTypeDef

            if (returnType.isScalarOrEnum()) {
                scalars.add(ScalarMutationFieldModel(field, pkg, baseTypeMapper))
            } else {
                complex.add(ComplexMutationFieldModel(field, pkg, baseTypeMapper))
            }
        }

        scalarFields = scalars
        complexFields = complex
    }
}

// =============================================================================
// StringTemplate Definition
// =============================================================================

private val MUTATION_DSL_TEMPLATE = stTemplate(
    """
@file:Suppress("warnings")

package <mdl.pkg>

/**
 * Creates a GraphQL mutation string using a type-safe DSL.
 *
 * @param name Optional operation name for the mutation
 * @param block DSL block to define mutation fields
 * @return The GraphQL mutation string
 */
fun mutation(name: String? = null, block: MutationDslBuilder.() -> Unit): String {
    val builder = MutationDslBuilder()
    builder.block()
    val operationName = name?.let { " ${'$'}it" } ?: ""
    return "mutation${'$'}operationName { ${'$'}{builder.build()} }"
}

/**
 * DSL builder for constructing GraphQL mutations.
 */
class MutationDslBuilder internal constructor() {
    private val fields = mutableListOf\<String>()

    private fun addField(name: String) {
        fields.add(name)
    }

<mdl.scalarFields: { f |
    fun <f.escapedName>(<f.parameterSignature>) {
        val aliasPrefix = if (alias != null) alias + ": " else ""
<if(f.hasArgs)>
        val args = listOf(<f.parameterSerializers>).joinToString(", ")
        addField(aliasPrefix + "<f.fieldName>(${'$'}args)")
<else>
        addField(aliasPrefix + "<f.fieldName>")
<endif>
    \}
}; separator="\n">

<mdl.complexFields: { f |
    fun <f.escapedName>(<f.parameterSignature>, block: <f.selectionBuilderType>.() -> Unit) {
        val aliasPrefix = if (alias != null) alias + ": " else ""
        val nestedBuilder = <f.selectionBuilderType>()
        nestedBuilder.block()
<if(f.hasInputArgs)>
        val argsMap = nestedBuilder.buildArgs()
        val argsStr = serializeArgsMap(argsMap)
        val argsSection = if (argsStr.isNotEmpty()) "(${'$'}argsStr)" else ""
        addField(aliasPrefix + "<f.fieldName>" + argsSection + " { " + nestedBuilder.build() + " \}")
<elseif(f.serializeArgsInline)>
        val args = listOf(<f.parameterSerializers>).joinToString(", ")
        addField(aliasPrefix + "<f.fieldName>(${'$'}args) { " + nestedBuilder.build() + " \}")
<else>
        addField(aliasPrefix + "<f.fieldName> { " + nestedBuilder.build() + " \}")
<endif>
    \}
}; separator="\n">

    internal fun build(): String = fields.joinToString(" ")

    private fun serializeArgsMap(args: Map\<String, Any?>): String {
        return args.entries.joinToString(", ") { (k, v) -> "${'$'}k: ${'$'}{serializeValue(v)}" }
    }

    private fun serializeValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\\"" + value.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"") + "\\""
            is Boolean -> value.toString()
            is Number -> value.toString()
            is Enum\<*> -> value.name
            is Map\<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) ->
                "${'$'}k: ${'$'}{serializeValue(v)}"
            }
            is List\<*> -> "[" + value.joinToString(", ") { serializeValue(it) } + "]"
            else -> value.toString()
        }
    }
}
"""
)
