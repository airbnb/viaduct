package viaduct.service.runtime

import graphql.schema.GraphQLDirectiveContainer
import java.util.SortedSet
import viaduct.engine.api.ViaductSchema

/**
 * Returns all scopes defined in the schema.
 * Scopes are defined with the directive @scope
 *
 * @return a sorted set of all scopes defined in the schema
 */
fun ViaductSchema.scopes(): SortedSet<String> {
    val result = sortedSetOf<String>()

    this.schema.typeMap.values.forEach { type ->
        if (type !is GraphQLDirectiveContainer) {
            return@forEach
        }

        val allScopes = type.getAppliedDirectives("scope")
            .map {
                it.getArgument("to").getValue<List<String>>()!!
            }
            .flatten()
            .filter { it != "*" }
            .toSet()

        result.addAll(allScopes)
    }

    return result
}
