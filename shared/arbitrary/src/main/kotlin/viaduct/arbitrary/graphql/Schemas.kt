package viaduct.arbitrary.graphql

import graphql.introspection.Introspection.DirectiveLocation
import graphql.schema.GraphQLDirective
import graphql.schema.GraphQLSchema
import viaduct.graphql.utils.GraphQLTypeRelations

/** A bag of holding for often-queried data about a GraphQL schema */
internal interface Schemas {
    val schema: GraphQLSchema
    val rels: GraphQLTypeRelations
    val directivesByLocation: Map<DirectiveLocation, Set<GraphQLDirective>>

    companion object {
        private class Impl(override val schema: GraphQLSchema) : Schemas {
            override val rels: GraphQLTypeRelations = GraphQLTypeRelations(schema)

            override val directivesByLocation: Map<DirectiveLocation, Set<GraphQLDirective>> =
                schema.directives
                    .flatMap { dir ->
                        dir.validLocations().map { loc -> dir to loc }
                    }
                    .groupBy({ it.second }, { it.first })
                    .mapValues { it.value.toSet() }
        }

        operator fun invoke(schema: GraphQLSchema): Schemas = Impl(schema)
    }
}
