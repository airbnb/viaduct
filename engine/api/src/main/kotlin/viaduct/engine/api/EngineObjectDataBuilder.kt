package viaduct.engine.api

import graphql.schema.GraphQLObjectType

interface EngineObjectDataBuilder {
    val type: GraphQLObjectType

    /**
     * Store a value with the provided [selection] and [value].
     *
     * @param selection a GraphQL response key. In the common case of an unaliased
     *  field selection, this is a field name. In the rarer case of an aliased
     *  field selection, this is the alias name.
     */
    fun put(
        selection: String,
        value: Any?
    ): EngineObjectDataBuilder

    fun build(): EngineObjectData

    companion object {
        fun from(type: GraphQLObjectType): EngineObjectDataBuilder {
            return ResolvedEngineObjectData.Builder(type)
        }
    }
}
