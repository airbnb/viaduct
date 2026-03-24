package viaduct.graphql

import graphql.schema.GraphQLAppliedDirective
import graphql.schema.GraphQLDirectiveContainer
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLObjectType

private val idOf: String = "idOf"

/** return true if the provided field should have a GlobalID GRT type */
fun isGlobalID(
    field: GraphQLFieldDefinition,
    parentType: GraphQLObjectType
): Boolean = field.name == "id" && parentType.interfaces.any { it.name == "Node" } || field.hasIdOfDirective

/** return true if the provided field should have a GlobalID GRT type */
fun isGlobalID(field: GraphQLInputObjectField): Boolean = field.hasIdOfDirective

/**
 * Parse the `type` argument from the provided [dir].
 *
 * Precondition: [dir.name] must be "idOf"
 */
private fun globalIDType(dir: GraphQLAppliedDirective): String {
    require(dir.name == idOf)
    return dir.getArgument("type").getValue()
}

/**
 * Returns the GlobalID type name for a field.
 * If the field has an `@idOf(type: "...")` directive, returns that type string.
 * Otherwise falls back to [parentType]'s name.
 *
 * Precondition: [isGlobalID] must be true for the given field and parent type.
 */
fun globalIDType(
    field: GraphQLFieldDefinition,
    parentType: GraphQLObjectType
): String {
    assert(isGlobalID(field, parentType))
    return if (field.hasIdOfDirective) {
        globalIDType(requireNotNull(field.idOfDirective))
    } else {
        parentType.name
    }
}

/**
 * Returns the GlobalID type name for an input field, extracted from the @idOf directive
 * Precondition: [isGlobalID] must be true for the given field and parent type.
 */
fun globalIDType(field: GraphQLInputObjectField): String {
    assert(isGlobalID(field))
    return globalIDType(requireNotNull(field.idOfDirective))
}

/** Returns true if this directive container has an applied `@idOf` directive. */
val GraphQLDirectiveContainer.hasIdOfDirective: Boolean get() =
    appliedDirectives.any { it.name == idOf }

/** Returns the applied `@idOf` directive, or null if absent. */
val GraphQLDirectiveContainer.idOfDirective: GraphQLAppliedDirective? get() =
    appliedDirectives.firstOrNull { it.name == idOf }
