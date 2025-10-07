package viaduct.engine.api

import graphql.schema.GraphQLObjectType

/**
 * A representation of a GraphQL object type
 */
interface EngineObject {
    val graphQLObjectType: GraphQLObjectType
}
