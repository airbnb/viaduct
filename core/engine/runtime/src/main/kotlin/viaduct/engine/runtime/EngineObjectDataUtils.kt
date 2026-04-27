package viaduct.engine.runtime

import graphql.introspection.Introspection
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.EngineSelectionSet

internal object EngineObjectDataUtils {
    private val introspectionFields = listOf(
        Introspection.TypeNameMetaFieldDef,
        Introspection.SchemaMetaFieldDef,
        Introspection.TypeMetaFieldDef
    ).associateBy { it.name }

    /**
     * Returns the sub-[EngineSelectionSet] for [selectionName] if [fieldName] resolves to a composite
     * type (object, interface, or union), or null otherwise. Handles introspection meta-fields
     * (e.g. `__typename`) which are not present on the [GraphQLObjectType] itself.
     * Throws [IllegalStateException] if [fieldName] is not found on [objectType] and is not an
     * introspection field.
     */
    fun maybeSubselections(
        objectType: GraphQLObjectType,
        fieldName: String,
        selectionName: String,
        selectionSet: EngineSelectionSet,
    ): EngineSelectionSet? {
        val field = objectType.getField(fieldName) ?: introspectionFields[fieldName]
            ?: error("Unknown field '$fieldName' on type '${objectType.name}'")
        return if (GraphQLTypeUtil.unwrapAll(field.type) is GraphQLCompositeType) {
            selectionSet.selectionSetForSelection(objectType.name, selectionName)
        } else {
            null
        }
    }
}
