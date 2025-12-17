/**
 * Object DSL Generator.
 *
 * Generates type-safe Kotlin DSL builders for GraphQL Object types.
 * These builders are used for nested field selection in queries and mutations.
 *
 * Input types are passed as `Map<String, Any?>` for idiomatic Kotlin DSL usage.
 *
 * @see QueryDslGenerator for query-level generation
 * @see MutationDslGenerator for mutation-level generation
 * @see NodeInterfaceDslGenerator for interface builder generation
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
 * Generates a DSL builder class for a GraphQL Object type.
 *
 * @param pkg The target package name for the generated DSL classes
 * @param objectDef The GraphQL Object type definition
 * @param baseTypeMapper The type mapper for GraphQL to Kotlin type conversion
 * @return STContents ready to be written to a file
 */
fun objectDslGen(
    pkg: String,
    objectDef: ViaductSchema.Object,
    baseTypeMapper: BaseTypeMapper
): STContents = STContents(
    OBJECT_DSL_TEMPLATE,
    ObjectDslModelImpl(pkg, objectDef, baseTypeMapper)
)

// =============================================================================
// Template Model Interface
// =============================================================================

private interface ObjectDslModel {
    val pkg: String
    val typeName: String
    val className: String
    val scalarFields: List<ObjectScalarFieldModel>
    val complexFields: List<ObjectComplexFieldModel>
}

// =============================================================================
// Field Model Classes
// =============================================================================

private class ObjectScalarFieldModel(fieldDef: ViaductSchema.Field) {
    val escapedName: String = getEscapedFieldName(fieldDef.name)
    val fieldName: String = fieldDef.name
    val graphqlType: String = fieldDef.type.toString()
}

private class ObjectComplexFieldModel(
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

    val needsSelection: Boolean = returnTypeDef.requiresSelectionSet()

    val selectionBuilderType: String? =
        if (needsSelection) "${returnTypeDef.name}DslBuilder" else null

    val hasArgs: Boolean = fieldDef.args.isNotEmpty()
    val parameterSignature: String = buildParameterSignature(parameters, includeAlias = false)
    val parameterSerializers: String = buildParameterSerializers(parameters)
}

// =============================================================================
// Template Model Implementation
// =============================================================================

private class ObjectDslModelImpl(
    override val pkg: String,
    objectDef: ViaductSchema.Object,
    baseTypeMapper: BaseTypeMapper
) : ObjectDslModel {

    override val typeName: String = objectDef.name
    override val className: String = "${objectDef.name}DslBuilder"

    override val scalarFields: List<ObjectScalarFieldModel>
    override val complexFields: List<ObjectComplexFieldModel>

    init {
        val scalars = mutableListOf<ObjectScalarFieldModel>()
        val complex = mutableListOf<ObjectComplexFieldModel>()

        for (field in objectDef.fields) {
            val returnType = field.type.baseTypeDef
            val isSimpleScalar = returnType.isScalarOrEnum() && field.args.isEmpty()

            if (isSimpleScalar) {
                scalars.add(ObjectScalarFieldModel(field))
            } else {
                complex.add(ObjectComplexFieldModel(field, pkg, baseTypeMapper))
            }
        }

        scalarFields = scalars
        complexFields = complex
    }
}

// =============================================================================
// StringTemplate Definition
// =============================================================================

private val OBJECT_DSL_TEMPLATE = stTemplate(
    """
@file:Suppress("warnings")

package <mdl.pkg>

/**
 * DSL builder for selecting fields from the `<mdl.typeName>` GraphQL type.
 */
open class <mdl.className> internal constructor() {
    protected val fields = mutableListOf\<String>()

    protected fun addField(name: String) {
        fields.add(name)
    }

<mdl.scalarFields: { f |
    val <f.escapedName>: Unit
        get() {
            addField("<f.fieldName>")
        \}
}; separator="\n">

<mdl.complexFields: { f |
    fun <f.escapedName>(<f.parameterSignature><if(f.needsSelection)><if(f.hasArgs)>, <endif>block: <f.selectionBuilderType>.() -> Unit<endif>) {
<if(f.hasArgs)>
        val args = listOf(<f.parameterSerializers>).joinToString(", ")
        val fieldStr = "<f.fieldName>(${'$'}args)"
<else>
        val fieldStr = "<f.fieldName>"
<endif>
<if(f.needsSelection)>
        val nestedBuilder = <f.selectionBuilderType>()
        nestedBuilder.block()
        addField("${'$'}fieldStr { ${'$'}{nestedBuilder.build()\} \}")
<else>
        addField(fieldStr)
<endif>
    \}
}; separator="\n">

    internal open fun build(): String = fields.joinToString(" ")

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
