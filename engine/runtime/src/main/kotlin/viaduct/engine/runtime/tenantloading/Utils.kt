@file:Suppress("MatchingDeclarationName")

package viaduct.engine.runtime.tenantloading

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.Coordinate
import viaduct.engine.api.RawSelection
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.GraphQLTypeRelation
import viaduct.graphql.utils.VariableUsageInfo

internal data class Type(
    val type: GraphQLType,
    val properties: Set<Property>
) {
    constructor(type: GraphQLType) : this(type, emptySet())

    operator fun plus(property: Property): Type = copy(properties = properties + property)

    operator fun minus(property: Property): Type = copy(properties = properties - property)

    private fun unwrapOne(): Type = copy(type = GraphQLTypeUtil.unwrapOneAs(type))

    /** if [isNonNull] is true, return an unwrapped Type, otherwise return the current object */
    val unwrapIfNonNull: Type get() = if (isNonNull) unwrapOne() else this

    /** if [isList] is true, return an unwrapped Type, otherwise return the current object */
    val unwrapIfList: Type get() = if (isList) unwrapOne() else this

    /** true if [type] is a GraphQLNonNull */
    val isNonNull: Boolean get() = GraphQLTypeUtil.isNonNull(type)

    /** true if [type] is a GraphQLList */
    val isList: Boolean get() = GraphQLTypeUtil.isList(type)

    enum class Property {
        /** indicates that a Type is accessed through a path that may contain null values */
        NullableTraversalPath,

        /** indicates that a Type may be satisfied by a default argument or input field value */
        HasDefault,
    }

    /** true if [type] is nullable or this Type contains [Property.NullableTraversalPath] */
    val effectivelyNullable: Boolean = GraphQLTypeUtil.isNullable(type) || Property.NullableTraversalPath in properties

    companion object {
        operator fun invoke(usage: VariableUsageInfo): Type {
            var t = Type(usage.type)
            if (usage.hasDefaultValue) t += Property.HasDefault
            return t
        }
    }
}

/** true if the provided GraphQLType is a list, or a non-nullable list */
internal val GraphQLType.isListish: Boolean get() =
    when (this) {
        is GraphQLList -> true
        is GraphQLNonNull -> wrappedType.isListish
        else -> false
    }

/** return a [Coordinate] representation of a [RawSelection] */
internal val RawSelection.coord: Coordinate get() = this.typeCondition to this.fieldName

/**
 * return a [GraphQLTypeRelation.Relation] that describes the relationship
 * between this RawSelectionSet and the type condition of the provided [selection]
 */
internal fun RawSelectionSet.relation(
    schema: ViaductSchema,
    selection: RawSelection
): GraphQLTypeRelation.Relation {
    val ssType = schema.schema.getTypeAs<GraphQLCompositeType>(type)
    val selectionType = schema.schema.getTypeAs<GraphQLCompositeType>(selection.typeCondition)
    return schema.rels.relation(ssType, selectionType)
}

/**
 * Return true if [variableType] can be coerced to [locationType]
 *
 * This currently may return false for situations where coercion succeeds. If these use cases surface, this function
 * may be augmented to support them.
 */
internal tailrec fun areTypesCompatible(
    locationType: Type,
    variableType: Type,
): Boolean =
    when {
        // nullable values are acceptable if the location type has a default value
        Type.Property.HasDefault in locationType.properties && variableType.effectivelyNullable -> {
            areTypesCompatible(
                locationType.unwrapIfNonNull - Type.Property.HasDefault,
                variableType
            )
        }

        locationType.isNonNull -> {
            val unwrappedLocation = locationType.unwrapIfNonNull
            val unwrappedVariable = variableType.unwrapIfNonNull
            when {
                unwrappedLocation.isList -> {
                    // Avoid unwrapping non-null for variableType if it's not listish,
                    // e.g. String! (should not unwrap non-null) is coercible to [String!]
                    areTypesCompatible(unwrappedLocation, if (unwrappedVariable.isList) unwrappedVariable else variableType)
                }
                variableType.effectivelyNullable -> false
                else -> areTypesCompatible(unwrappedLocation, unwrappedVariable)
            }
        }

        locationType.isList -> {
            val unwrappedVariable = if (variableType.type.isListish) {
                variableType.unwrapIfNonNull.unwrapIfList
            } else {
                variableType
            }
            areTypesCompatible(locationType.unwrapIfList, unwrappedVariable)
        }

        else -> GraphQLTypeUtil.unwrapNonNull(locationType.type) == GraphQLTypeUtil.unwrapNonNull(variableType.type)
    }
