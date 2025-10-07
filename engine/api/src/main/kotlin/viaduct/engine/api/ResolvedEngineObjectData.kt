package viaduct.engine.api

import graphql.schema.GraphQLObjectType

class ResolvedEngineObjectData private constructor(
    override val graphQLObjectType: GraphQLObjectType,
    private val data: Map<String, Any?>
) : EngineObjectData.Sync {
    override suspend fun fetch(selection: String) = get(selection)

    override suspend fun fetchOrNull(selection: String) = getOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = data.keys

    override fun get(selection: String): Any? {
        if (!data.containsKey(selection)) {
            throw UnsetSelectionException(
                selection,
                graphQLObjectType,
                "Please set a value for $selection using the builder for ${graphQLObjectType.name}"
            )
        }
        return data[selection]
    }

    override fun getOrNull(selection: String): Any? = data[selection]

    class Builder(override val graphQLObjectType: GraphQLObjectType) : EngineObjectDataBuilder {
        private val data = mutableMapOf<String, Any?>()

        override fun put(
            selection: String,
            value: Any?
        ): Builder {
            data[selection] = value
            return this
        }

        override fun build() = ResolvedEngineObjectData(graphQLObjectType, data)
    }
}
