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
 * return true if [variableType] can be used in [locationType]
 *
 * A nuance to this method is that this validates only that [variableType] is compatible with [locationType].
 * This is subtly different from if a value that looks like [variableType] can be coerced to [locationType].
 *
 * For a motivating example, consider this schema and query:
 *
 * ```graphql
 *   # schema
 *   type Query { x(a:String):String }
 *
 *   # query
 *   query ($i:Int = 0) {
 *     x1: x(a:$i)
 *     x2: x(a:0)
 *   }
 * ```
 *
 * While both of these selections attempt to pass a literal `0` value to a location that requires a String,
 * the x2 selection is valid while the x1 selection is not.
 *
 * This is because GraphQL coercion only applies to how a value literal is coerced into a value for a given type,
 * and not how a type like Int can be coerced to a location expecting String.
 *
 * This method implements the type validation exhibited by the x1 selection.
 */
internal tailrec fun areTypesCompatible(
    locationType: Type,
    variableType: Type,
): Boolean =
    if (Type.Property.HasDefault in locationType.properties && variableType.effectivelyNullable) {
        // nullable values are acceptable if the location type has a default value
        areTypesCompatible(
            locationType.unwrapIfNonNull - Type.Property.HasDefault,
            variableType
        )
    } else if (locationType.isNonNull) {
        if (variableType.effectivelyNullable) {
            false
        } else {
            areTypesCompatible(locationType.unwrapIfNonNull, variableType.unwrapIfNonNull)
        }
    } else if (locationType.isList && variableType.isList) {
        areTypesCompatible(locationType.unwrapIfList, variableType.unwrapIfList)
    } else {
        GraphQLTypeUtil.unwrapNonNull(locationType.type) == GraphQLTypeUtil.unwrapNonNull(variableType.type)
    }
