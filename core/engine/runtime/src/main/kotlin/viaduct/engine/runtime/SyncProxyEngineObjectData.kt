package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import viaduct.engine.api.EngineObjectData
import viaduct.errors.UnsetFieldException

/**
 * A synchronous implementation of [EngineObjectData.Sync] that stores eagerly-resolved data
 * from an [ObjectEngineResult].
 *
 * Resolves all data upfront during construction. Field-level errors are deferred until
 * the field is accessed — errors are stored in the backing map as [Exception] instances
 * and rethrown when the field is read.
 *
 * Created by [SyncEngineObjectDataFactory].
 *
 * @param type the concrete GraphQL object type that this data describes
 * @param data a map of data keyed by selection name; values may be [Exception] to indicate
 *        a field-level error that should be thrown when accessed
 * @param errorMessageTemplate optional custom error message template for [UnsetFieldException]
 * @param conditionallyExcludedResultKeys result keys absent because @skip/@include evaluated to
 *        a definite drop; [get] returns null for these rather than throwing [UnsetFieldException]
 */
class SyncProxyEngineObjectData(
    override val type: GraphQLObjectType,
    private val data: Map<String, Any?>,
    private val errorMessageTemplate: String? = null,
    private val conditionallyExcludedResultKeys: Set<String> = emptySet(),
) : EngineObjectData.Sync {
    override suspend fun fetch(selection: String) = get(selection)

    override suspend fun fetchOrNull(selection: String) = getOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = getSelections()

    override fun getSelections(): Iterable<String> = data.keys

    override fun isPresent(selection: String): Boolean = data.containsKey(selection) || selection in conditionallyExcludedResultKeys

    override fun get(selection: String): Any? {
        if (!isPresent(selection)) {
            val message = errorMessageTemplate
                ?: "Please set a value for $selection using the builder for ${type.name}"
            throw UnsetFieldException(
                selection,
                type,
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
        if (!isPresent(selection)) return null
        val value = data[selection]
        if (value is Exception) {
            throw value
        }
        return value
    }

    override fun toString(): String = "SyncProxyEngineObjectData(type=${type.name}, data=$data)"
}
