/**
 * Query DSL Generator.
 *
 * Generates type-safe Kotlin DSL code for building GraphQL queries.
 *
 * ## Generated Code Structure
 *
 * The generator produces:
 * 1. A top-level `query()` function that creates and executes the builder
 * 2. A `QueryDslBuilder` class with:
 *    - Scalar fields as Kotlin properties (get-only)
 *    - Complex fields (Objects, Interfaces, Unions) as functions with nested builder blocks
 *    - Support for field arguments and aliases
 *
 * ## Usage Example
 *
 * Given this GraphQL schema:
 * ```graphql
 * type Query {
 *     greeting: String
 *     user(id: ID!): User
 *     searchUsers(search: UserSearchInput!): [User]
 * }
 * ```
 *
 * The generated DSL allows:
 * ```kotlin
 * val queryString = query("GetUser") {
 *     greeting
 *     user(id = "123") {
 *         id
 *         name
 *     }
 *     searchUsers {
 *         search {
 *             name = "Luke"
 *         }
 *         id
 *         name
 *     }
 * }
 * ```
 *
 * Fields with input type arguments use specialized query builders for type-safe DSL usage.
 *
 * @see MutationDslGenerator for mutation generation
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
 * Generates the Query DSL code for a GraphQL Query type.
 *
 * @param pkg The target package name for the generated DSL classes
 * @param queryDef The GraphQL Query object type definition
 * @param baseTypeMapper The type mapper for GraphQL to Kotlin type conversion
 * @return STContents ready to be written to a file
 */
fun queryDslGen(
    pkg: String,
    queryDef: ViaductSchema.Object,
    baseTypeMapper: BaseTypeMapper
): STContents = STContents(
    QUERY_DSL_TEMPLATE,
    QueryDslModelImpl(pkg, queryDef, baseTypeMapper)
)

// =============================================================================
// Template Model Interface
// =============================================================================

/**
 * Template model interface for Query DSL generation.
 */
private interface QueryDslModel {
    val pkg: String
    val scalarFields: List<ScalarFieldModel>
    val complexFields: List<ComplexFieldModel>
}

// =============================================================================
// Field Model Classes
// =============================================================================

/**
 * Model for scalar fields without arguments in the Query DSL.
 */
private class ScalarFieldModel(fieldDef: ViaductSchema.Field) {
    val escapedName: String = getEscapedFieldName(fieldDef.name)
    val fieldName: String = fieldDef.name
    val graphqlType: String = fieldDef.type.toString()
}

/**
 * Model for complex fields or scalar fields with arguments in the Query DSL.
 */
private class ComplexFieldModel(
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

    // Check if this field has input type arguments - if so, use specialized builder
    val hasInputArgs: Boolean = fieldDef.args.any { it.type.baseTypeDef is ViaductSchema.Input }

    // Use specialized query builder if has input args, otherwise use standard selection builder
    val selectionBuilderType: String = if (hasInputArgs && needsSelection) {
        getQueryFieldBuilderName(fieldDef.name)
    } else if (needsSelection) {
        "${returnTypeDef.name}DslBuilder"
    } else {
        ""
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

/**
 * Implementation of [QueryDslModel] that processes the GraphQL Query type
 * and classifies fields into scalar and complex categories.
 */
private class QueryDslModelImpl(
    override val pkg: String,
    queryDef: ViaductSchema.Object,
    baseTypeMapper: BaseTypeMapper
) : QueryDslModel {

    override val scalarFields: List<ScalarFieldModel>
    override val complexFields: List<ComplexFieldModel>

    init {
        val scalars = mutableListOf<ScalarFieldModel>()
        val complex = mutableListOf<ComplexFieldModel>()

        for (field in queryDef.fields) {
            val returnType = field.type.baseTypeDef
            val isSimpleScalar = returnType.isScalarOrEnum() && field.args.isEmpty()

            if (isSimpleScalar) {
                scalars.add(ScalarFieldModel(field))
            } else {
                complex.add(ComplexFieldModel(field, pkg, baseTypeMapper))
            }
        }

        scalarFields = scalars
        complexFields = complex
    }
}

// =============================================================================
// StringTemplate Definition
// =============================================================================

private val QUERY_DSL_TEMPLATE = stTemplate(
    """
@file:Suppress("warnings")

package <mdl.pkg>

/**
 * Creates a GraphQL query string using a type-safe DSL.
 *
 * @param name Optional operation name for the query
 * @param block DSL block to define query fields
 * @return The GraphQL query string
 */
fun query(name: String? = null, block: QueryDslBuilder.() -> Unit): String {
    val builder = QueryDslBuilder()
    builder.block()
    val operationName = name?.let { " ${'$'}it" } ?: ""
    return "query${'$'}operationName { ${'$'}{builder.build()} }"
}

/**
 * DSL builder for constructing GraphQL queries.
 */
class QueryDslBuilder internal constructor() {
    private val fields = mutableListOf\<String>()

    private fun addField(name: String) {
        fields.add(name)
    }

<mdl.scalarFields: { f |
    val <f.escapedName>: Unit
        get() {
            addField("<f.fieldName>")
        \}
}; separator="\n">

<mdl.complexFields: { f |
    fun <f.escapedName>(<f.parameterSignature><if(f.needsSelection)>, block: <f.selectionBuilderType>.() -> Unit<endif>) {
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
