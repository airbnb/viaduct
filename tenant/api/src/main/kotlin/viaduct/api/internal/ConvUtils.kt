package viaduct.api.internal

import graphql.introspection.Introspection
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.ConvMemo

/**
 * If [predicate] is true, then [buildIfAbsent] will be applied to the provided [memoKey] and [fn]
 * Otherwise, [fn] will be run and its result returned without being memoized.
 */
internal fun <From, To> ConvMemo.memoizeIf(
    memoKey: String,
    predicate: Boolean,
    fn: () -> Conv<From, To>
): Conv<From, To> =
    if (predicate) {
        buildIfAbsent(memoKey, fn)
    } else {
        fn()
    }

/** returns true if a selection set can be applied to this type */
internal val GraphQLType.supportsSelections: Boolean get() =
    GraphQLTypeUtil.unwrapAllAs<GraphQLType>(this) is GraphQLCompositeType

/** return a list of all [GraphQLFieldDefinition]s under this object that can be mapped */
internal val GraphQLCompositeType.mappableFields: List<GraphQLFieldDefinition> get() =
    if (this is GraphQLFieldsContainer) {
        this.fields + Introspection.TypeNameMetaFieldDef
    } else {
        listOf(Introspection.TypeNameMetaFieldDef)
    }

/**
 * Create a Map of [Conv]'s that describe the mapping of [type]
 *
 * @param type the GraphQL type for which [Conv]s will be generated
 * @param selectionSet a possible projection of [type]
 * @return a map of convs that can map the properties of [type] with possible projection [selectionSet].
 *   If [selectionSet] is null, the returned Map will be keyed by field name
 *   If [selectionSet] is not null, the returned Map will be keyed by selection name
 */
internal fun <From, To> mkSelectionConvs(
    schema: ViaductSchema,
    type: GraphQLObjectType,
    selectionSet: RawSelectionSet?,
    buildFieldConv: (GraphQLType, RawSelectionSet?) -> Conv<From, To>,
): Map<String, Conv<From, To>> =
    if (selectionSet != null) {
        selectionSet.selections().associate { sel ->
            val coord = (sel.typeCondition to sel.fieldName).gj
            val fieldDef = schema.schema.getFieldDefinition(coord)
            val subSelections = if (fieldDef.type.supportsSelections) {
                selectionSet.selectionSetForSelection(sel.typeCondition, sel.selectionName)
            } else {
                null
            }
            sel.selectionName to buildFieldConv(fieldDef.type, subSelections)
        }
    } else {
        type.mappableFields.associate { f ->
            f.name to buildFieldConv(f.type, null)
        }
    }
