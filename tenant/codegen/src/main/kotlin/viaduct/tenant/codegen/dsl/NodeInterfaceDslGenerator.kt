/**
 * Node Interface DSL Generator.
 *
 * Generates type-safe Kotlin DSL builders for GraphQL Interface types,
 * supporting inline fragments for type-specific field selection.
 *
 * @see ObjectDslGenerator for object builder generation
 * @see QueryDslGenerator for query-level generation
 */
package viaduct.tenant.codegen.dsl

import getEscapedFieldName
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.graphql.schema.ViaductSchema

// =============================================================================
// Public API
// =============================================================================

/**
 * Generates a DSL builder class for a GraphQL Interface type.
 *
 * @param pkg The target package name for the generated DSL classes
 * @param nodeInterface The GraphQL Interface type definition
 * @param implementingTypes List of Object types that implement this interface
 * @return STContents ready to be written to a file
 */
fun nodeInterfaceDslGen(
    pkg: String,
    nodeInterface: ViaductSchema.Interface,
    implementingTypes: List<ViaductSchema.Object>
): STContents = STContents(
    NODE_INTERFACE_TEMPLATE,
    NodeInterfaceModelImpl(pkg, nodeInterface, implementingTypes)
)

// =============================================================================
// Template Model Interface
// =============================================================================

/**
 * Template model interface for Interface DSL builder generation.
 *
 * This interface defines the data contract between the generator logic
 * and the StringTemplate.
 */
private interface NodeInterfaceModel {
    /** The target package name for generated code */
    val pkg: String

    /** The interface name (used to generate {InterfaceName}DslBuilder) */
    val interfaceName: String

    /** List of types that implement this interface */
    val implementingTypes: List<ImplementingTypeModel>

    /** List of common fields from the interface (scalar/enum only) */
    val commonFields: List<CommonFieldModel>
}

// =============================================================================
// Model Classes
// =============================================================================

/**
 * Model for a type that implements the interface.
 *
 * Used to generate fragment methods like `onUser()`, `onPost()`, etc.
 *
 * @property typeName The GraphQL type name
 * @property builderClassName The DSL builder class name for this type
 * @property fragmentMethodName The method name for the inline fragment (e.g., "onUser")
 */
private class ImplementingTypeModel(typeDef: ViaductSchema.Object) {
    val typeName: String = typeDef.name
    val builderClassName: String = "${typeDef.name}DslBuilder"
    val fragmentMethodName: String = "on${typeDef.name}"
}

/**
 * Model for a common field defined on the interface.
 *
 * Only scalar/enum fields without arguments are included as common fields.
 * Complex fields and fields with arguments must be accessed through
 * type-specific fragment methods.
 *
 * @property fieldName The GraphQL field name
 * @property escapedName The Kotlin-safe property name
 * @property graphqlType The GraphQL type string
 */
private class CommonFieldModel(fieldDef: ViaductSchema.Field) {
    val fieldName: String = fieldDef.name
    val escapedName: String = getEscapedFieldName(fieldDef.name)
    val graphqlType: String = fieldDef.type.toString()
}

// =============================================================================
// Template Model Implementation
// =============================================================================

/**
 * Implementation of [NodeInterfaceModel] that processes the GraphQL Interface type
 * and identifies implementing types and common fields.
 */
private class NodeInterfaceModelImpl(
    override val pkg: String,
    nodeInterface: ViaductSchema.Interface,
    implementingTypes: List<ViaductSchema.Object>
) : NodeInterfaceModel {

    override val interfaceName: String = nodeInterface.name

    override val implementingTypes: List<ImplementingTypeModel> =
        implementingTypes.map { ImplementingTypeModel(it) }

    /**
     * Filters interface fields to only include simple scalar/enum fields.
     *
     * Complex fields and fields with arguments are excluded because:
     * 1. They may have different argument types across implementing types
     * 2. They require type-specific builders for nested selections
     */
    override val commonFields: List<CommonFieldModel> =
        nodeInterface.fields
            .filter { field ->
                val returnType = field.type.baseTypeDef
                returnType.isScalarOrEnum() && field.args.isEmpty()
            }
            .map { CommonFieldModel(it) }
}

// =============================================================================
// StringTemplate Definition
// =============================================================================

/**
 * StringTemplate for generating {InterfaceName}DslBuilder.kt files.
 *
 * Template variables:
 * - `mdl.pkg`: Package name
 * - `mdl.interfaceName`: The interface name
 * - `mdl.commonFields`: List of common field models
 * - `mdl.implementingTypes`: List of implementing type models
 */
private val NODE_INTERFACE_TEMPLATE = stTemplate(
    """
@file:Suppress("warnings")

package <mdl.pkg>

/**
 * DSL builder for selecting fields from the `<mdl.interfaceName>` GraphQL interface.
 */
class <mdl.interfaceName>DslBuilder internal constructor() {
    private val fields = mutableListOf\<String>()

    private fun addField(name: String) {
        fields.add(name)
    }

<mdl.commonFields: { f |
    val <f.escapedName>: Unit
        get() {
            addField("<f.fieldName>")
        \}
}; separator="\n">

<mdl.implementingTypes: { t |
    fun <t.fragmentMethodName>(block: <t.builderClassName>.() -> Unit) {
        val nestedBuilder = <t.builderClassName>()
        nestedBuilder.block()
        addField("... on <t.typeName> { ${'$'}{nestedBuilder.build()\} \}")
    \}
}; separator="\n">

    internal fun build(): String = fields.joinToString(" ")
}
"""
)
