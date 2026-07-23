package viaduct.remote

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference

/**
 * Tagged JSON codec for field-resolver return values and argument maps.
 *
 * Field resolvers return `Any?` — scalars, `null`, node references, resolved objects, and lists
 * thereof. Unlike node resolvers (whose results are always an [EngineObjectData] of a statically
 * known type), a field value carries no out-of-band type, so each value is wrapped in a small
 * tagged envelope recording its kind — and, for references and objects, the concrete GraphQL type
 * name so the engine side can reconstruct against the live schema:
 *
 * | kind    | JSON shape                                     | notes                              |
 * |---------|------------------------------------------------|------------------------------------|
 * | null    | `{"k":"n"}`                                    |                                    |
 * | scalar  | `{"k":"s","v":<json scalar / list / map>}`     | JSON-friendly leaf values          |
 * | noderef | `{"k":"r","t":"<Type>","id":"<globalId>"}`     | rebuilt via `createNodeReference`  |
 * | object  | `{"k":"o","t":"<Type>","v":<field map>}`       | reuses [EngineObjectDataSerializer] |
 * | list    | `{"k":"l","items":[<tagged>, …]}`              | tagged per element, recursive      |
 *
 * [serializeValue] runs on the remote service (where the resolver ran); [deserializeValue] runs on
 * the engine side, which supplies an [EngineExecutionContext] to rebuild references and objects
 * against the live schema. The concrete type is read from [EngineObject.type][viaduct.engine.api.EngineObject.type]
 * on serialize, so interface/union return types (e.g. `node: Node`) reconstruct as their real object type.
 */
object FieldValueSerializer {
    private val objectMapper = jacksonObjectMapper()

    private const val KIND = "k"
    private const val VALUE = "v"
    private const val TYPE = "t"
    private const val ID = "id"
    private const val ITEMS = "items"

    private const val KIND_NULL = "n"
    private const val KIND_SCALAR = "s"
    private const val KIND_NODEREF = "r"
    private const val KIND_OBJECT = "o"
    private const val KIND_LIST = "l"

    /** Serializes a field result to tagged JSON bytes; throws for genuinely non-serializable values. */
    suspend fun serializeValue(value: Any?): ByteArray = objectMapper.writeValueAsBytes(toTagged(value))

    /** Inverse of [serializeValue]; rebuilds references/objects against [context]'s live schema. */
    fun deserializeValue(
        jsonBytes: ByteArray,
        context: EngineExecutionContext
    ): Any? {
        @Suppress("UNCHECKED_CAST")
        val tagged = objectMapper.readValue(jsonBytes, Map::class.java) as Map<String, Any?>
        return fromTagged(tagged, context)
    }

    /** Serializes a field-argument map to JSON bytes. */
    fun serializeArguments(arguments: Map<String, Any?>): ByteArray = objectMapper.writeValueAsBytes(arguments)

    /** Inverse of [serializeArguments]; an empty payload deserializes to an empty map. */
    fun deserializeArguments(jsonBytes: ByteArray): Map<String, Any?> {
        if (jsonBytes.isEmpty()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(jsonBytes, Map::class.java) as Map<String, Any?>
    }

    private suspend fun toTagged(value: Any?): Map<String, Any?> =
        when (value) {
            null -> mapOf(KIND to KIND_NULL)
            // A NodeReference is *also* an EngineObjectData (NodeEngineObjectDataImpl), so check it
            // first: ship the reference as {id, type} rather than eagerly resolving its fields.
            is NodeReference -> mapOf(KIND to KIND_NODEREF, TYPE to value.type.name, ID to value.id)
            is EngineObjectData.Sync -> mapOf(KIND to KIND_OBJECT, TYPE to value.type.name, VALUE to EngineObjectDataSerializer.serializeToMap(value))
            // Any other unresolved reference (e.g. a RootFieldReference) has no dedicated wire
            // representation here — unlike NodeReference, it can't be shipped lazily, and recursing
            // into it would await a resolution that never happens on the serialize path.
            is EngineObjectData -> throw UnsupportedOperationException(
                "Remote field resolvers cannot serialize an unresolved reference of type '${value.type.name}' " +
                    "(other than a node reference, which ships as {id, type}); resolve it before returning it."
            )
            // A map (e.g. a custom-scalar payload) ships as a JSON leaf, but its contents must be
            // JSON-friendly — map values aren't tagged, so an object nested in a map is unsupported.
            is Map<*, *> -> {
                rejectNonJsonFriendly(value)
                mapOf(KIND to KIND_SCALAR, VALUE to value)
            }
            // A list may hold references/objects, so tag per element rather than treat it as a leaf.
            // Match only List (not any Iterable) so a Set/Sequence isn't silently reshaped to a list on
            // the wire — non-list iterables fall through to the explicit rejection below.
            is List<*> -> mapOf(KIND to KIND_LIST, ITEMS to value.map { toTagged(it) })
            is String, is Boolean, is Number -> mapOf(KIND to KIND_SCALAR, VALUE to value)
            else -> throw UnsupportedOperationException(
                "Remote field resolvers cannot serialize a return value of type ${value::class.qualifiedName}; " +
                    "supported kinds are scalars, null, node references, resolved objects, and lists thereof."
            )
        }

    private fun fromTagged(
        tagged: Map<String, Any?>,
        context: EngineExecutionContext
    ): Any? =
        when (val kind = tagged[KIND]) {
            KIND_NULL -> null
            KIND_SCALAR -> tagged[VALUE]
            KIND_NODEREF -> {
                val id = tagged[ID] as? String
                    ?: throw UnsupportedOperationException("Remote field-value noderef is missing its 'id'")
                context.createNodeReference(id, objectType(tagged, context))
            }
            KIND_OBJECT -> {
                @Suppress("UNCHECKED_CAST")
                val fieldMap = tagged[VALUE] as? Map<String, Any?>
                    ?: throw UnsupportedOperationException("Remote field-value object is missing its field map")
                EngineObjectDataSerializer.buildFromMap(fieldMap, objectType(tagged, context))
            }
            KIND_LIST -> {
                val items = tagged[ITEMS] as? List<*>
                    ?: throw UnsupportedOperationException("Remote field-value list is missing its 'items'")
                items.map { element ->
                    @Suppress("UNCHECKED_CAST")
                    val tag = element as? Map<String, Any?>
                        ?: throw UnsupportedOperationException("Remote field-value list element is not tagged")
                    fromTagged(tag, context)
                }
            }
            else -> throw UnsupportedOperationException("Unknown remote field-value kind '$kind'")
        }

    // Resolves the concrete GraphQL object type recorded on the wire against the live (full) schema.
    private fun objectType(
        tagged: Map<String, Any?>,
        context: EngineExecutionContext
    ): graphql.schema.GraphQLObjectType {
        val typeName = tagged[TYPE] as? String
            ?: throw UnsupportedOperationException("Remote field-value tag is missing its type name")
        return context.fullSchema.schema.getObjectType(typeName)
            ?: throw UnsupportedOperationException("Remote field-value references unknown GraphQL type '$typeName'")
    }

    // Walks a map's values so an object/reference slipped inside a map (which is shipped untagged
    // as a JSON leaf) is rejected rather than silently mangled on the wire.
    private fun rejectNonJsonFriendly(value: Any?) {
        when (value) {
            null, is String, is Boolean, is Number -> Unit
            is Map<*, *> -> value.values.forEach { rejectNonJsonFriendly(it) }
            is List<*> -> value.forEach { rejectNonJsonFriendly(it) }
            else -> throw UnsupportedOperationException(
                "Remote field resolvers cannot serialize a map/list containing ${value::class.qualifiedName}; " +
                    "map and list-leaf contents must be JSON-friendly (scalars, null, lists, maps)."
            )
        }
    }
}
