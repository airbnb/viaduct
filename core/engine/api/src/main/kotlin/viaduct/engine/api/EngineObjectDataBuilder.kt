package viaduct.engine.api

import graphql.schema.GraphQLObjectType

/**
 * Mutable builder for constructing an [EngineObjectData] value.
 *
 * Callers call [put] once per resolved field selection (using the GraphQL response key —
 * either the field name or its alias) and then call [build] to produce the immutable
 * [EngineObjectData]. The [type] property records the concrete [graphql.schema.GraphQLObjectType]
 * that the resulting data represents, which the engine uses for type-dispatch.
 *
 * Use [Companion.from] to create an instance for a given object type.
 */
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

    fun build(): EngineObjectData.Sync

    companion object {
        fun from(type: GraphQLObjectType): EngineObjectDataBuilder {
            return ResolvedEngineObjectData.Builder(type)
        }
    }
}
