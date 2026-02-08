package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.UnsetSelectionException

/**
 * A synchronous implementation of [EngineObjectData.Sync] that stores eagerly-resolved data
 * from an [ObjectEngineResult].
 *
 * Unlike [ProxyEngineObjectData] which lazily fetches data on access, this class
 * resolves all data upfront during construction. However, like [ProxyEngineObjectData],
 * field-level errors are deferred until the field is accessed - errors are stored in the
 * backing map as [Exception] instances and rethrown when the field is read.
 *
 * This is the synchronous counterpart to [ProxyEngineObjectData], created by
 * [SyncEngineObjectDataFactory].
 *
 * @param graphQLObjectType the concrete GraphQL object type that this data describes
 * @param data a map of data keyed by selection name; values may be [Exception] to indicate
 *        a field-level error that should be thrown when accessed
 * @param errorMessageTemplate optional custom error message template for [UnsetSelectionException]
 */
class SyncProxyEngineObjectData(
    override val graphQLObjectType: GraphQLObjectType,
    private val data: Map<String, Any?>,
    private val errorMessageTemplate: String? = null,
) : EngineObjectData.Sync {
    override suspend fun fetch(selection: String) = get(selection)

    override suspend fun fetchOrNull(selection: String) = getOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = getSelections()

    override fun getSelections(): Iterable<String> = data.keys

    override fun get(selection: String): Any? {
        if (!data.containsKey(selection)) {
            val message = errorMessageTemplate
                ?: "Please set a value for $selection using the builder for ${graphQLObjectType.name}"
            throw UnsetSelectionException(
                selection,
                graphQLObjectType,
                message
            )
        }
        val value = data[selection]
        if (value is Exception) {
            throw value
        }
        return value
    }

    override fun getOrNull(selection: String): Any? {
        val value = data[selection]
        if (value is Exception) {
            throw value
        }
        return value
    }

    override fun toString(): String = "SyncProxyEngineObjectData(type=${graphQLObjectType.name}, data=$data)"
}
