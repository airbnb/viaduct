package viaduct.engine.runtime.select

import graphql.language.Field
import graphql.schema.GraphQLCompositeType
import viaduct.graphql.utils.GraphQLTypeRelation

/**
 * Return a Map of selected [Field]s of a RawSelectionSet that are defined on the selected
 * type.
 *
 * The keys in this map are field names and always correspond to a field defined on the
 * current [type].
 *
 * The [Field] values in this map are not merged -- values in this map may have different
 * aliases, arguments, query directives, or originate from fragments with different
 * type conditions. Field values in this map are guaranteed to be defined on the underlying
 * GraphQL type that this RawSelectionSet is on.
 */
internal val RawSelectionSetImpl.typeFields: Map<String, List<Field>>
    get() {
        return selections
            .filter { (_, t) ->
                val type = ctx.schema.schema.getTypeAs<GraphQLCompositeType>(this.type)
                val rel = ctx.schema.rels.relationUnwrapped(type, t)
                rel == GraphQLTypeRelation.NarrowerThan || rel == GraphQLTypeRelation.Same
            }
            .groupBy({ it.field.name }, { it.field })
    }
