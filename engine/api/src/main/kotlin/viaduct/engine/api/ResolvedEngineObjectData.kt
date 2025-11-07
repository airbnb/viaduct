package viaduct.engine.api

import graphql.schema.GraphQLObjectType

/**
 * An implementation of [EngineObjectData.Sync], which models resolved untyped data.
 * Data contained by this object will not be type-checked, and is expected to conform
 * to the type-invariants described in [EngineObjectData].
 * Deviations from these invariants are likely to cause failures in other parts of Viaduct.
 *
 * @param graphQLObjectType the concrete GraphQL object type that this data describes
 * @param data a map of data, keyed by selection name.
 */
class ResolvedEngineObjectData(
    override val graphQLObjectType: GraphQLObjectType,
    private val data: Map<String, Any?>
) : EngineObjectData.Sync {
    override suspend fun fetch(selection: String) = get(selection)

    override suspend fun fetchOrNull(selection: String) = getOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = data.keys

    override fun getSelections(): Iterable<String> = data.keys

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

    override fun toString(): String = "type=${graphQLObjectType.name} $data=$data"

    /** Construct a Builder for [ResolvedEngineObjectData] */
    class Builder(override val graphQLObjectType: GraphQLObjectType) : EngineObjectDataBuilder {
        private val data = mutableMapOf<String, Any?>()

        /**
         * Store a value with the provided [selection] and [value].
         * The value of [value] will not be type-checked, and is expected to conform to the
         * type-invariants described in [EngineObjectData].
         * Deviations from these invariants are likely to cause failures in other parts of
         * Viaduct.
         *
         * @param selection a GraphQL response key
         * @param value a value for [selection]
         */
        override fun put(
            selection: String,
            value: Any?
        ): Builder {
            data[selection] = value
            return this
        }

        override fun build() = ResolvedEngineObjectData(graphQLObjectType, data.toMap())
    }
}
