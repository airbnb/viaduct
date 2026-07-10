package viaduct.remote

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import graphql.schema.GraphQLObjectType
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData

/**
 * JSON codec for [EngineObjectData].
 *
 * The wire format does not carry [GraphQLObjectType] identity or GraphQL-level
 * scalar/enum encoding. Nested objects and lists of objects are reconstructed
 * structurally: any JSON object value is re-wrapped into a [ResolvedEngineObjectData]
 * under a placeholder type so downstream consumers that walk the result with
 * [EngineObjectData.fetchOrNull] get the expected interface at every depth.
 */
object EngineObjectDataSerializer {
    private val objectMapper = jacksonObjectMapper()

    // Placeholder type for nested objects; mirrors the existing top-level pattern of
    // attaching a name-only GraphQLObjectType when true type identity isn't on the wire.
    private val NESTED_OBJECT_TYPE: GraphQLObjectType =
        GraphQLObjectType.newObject().name("RemoteNestedObject").build()

    suspend fun serialize(data: EngineObjectData): ByteArray = objectMapper.writeValueAsBytes(serializeToMap(data))

    suspend fun deserialize(
        jsonBytes: ByteArray,
        graphQLObjectType: GraphQLObjectType
    ): EngineObjectData.Sync {
        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.readValue(jsonBytes, Map::class.java) as Map<String, Any?>
        return buildFromMap(dataMap, graphQLObjectType)
    }

    /** Serializes an [EngineObjectData] to a JSON-friendly field map; nested objects and lists recurse. */
    internal suspend fun serializeToMap(data: EngineObjectData): Map<String, Any?> {
        val selections = data.fetchSelections()
        val dataMap = mutableMapOf<String, Any?>()
        for (selection in selections) {
            dataMap[selection] = serializeChild(data.fetchOrNull(selection))
        }
        return dataMap
    }

    // A nested NodeReference can't be serialized here: NodeEngineObjectDataImpl implements both
    // NodeReference and EngineObjectData, so recursing into serializeToMap would await a resolution
    // that never happens on the serialize path (it hangs until the request deadline). Fail fast — the
    // caller isolates it to a per-selector/-node error rather than hanging the whole batch.
    private suspend fun serializeChild(value: Any?): Any? =
        when (value) {
            is NodeReference -> throw UnsupportedOperationException(
                "Cannot serialize a nested NodeReference (type '${value.type.name}'); " +
                    "resolve the reference before returning it."
            )
            is EngineObjectData -> serializeToMap(value)
            is List<*> -> value.map { serializeChild(it) }
            else -> value
        }

    /** Rebuilds an [EngineObjectData] from a field map produced by [serializeToMap], under [type]. */
    internal fun buildFromMap(
        dataMap: Map<String, Any?>,
        type: GraphQLObjectType
    ): EngineObjectData.Sync {
        val builder = ResolvedEngineObjectData.Builder(type)
        for ((key, value) in dataMap) {
            builder.put(key, rewrap(value))
        }
        return builder.build()
    }

    private fun rewrap(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                buildFromMap(value as Map<String, Any?>, NESTED_OBJECT_TYPE)
            }
            is List<*> -> value.map { rewrap(it) }
            else -> value
        }
}
