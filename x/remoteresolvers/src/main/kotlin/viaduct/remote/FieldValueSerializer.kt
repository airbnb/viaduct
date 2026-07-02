package viaduct.remote

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * JSON codec for field-resolver return values and argument maps.
 *
 * Results are wrapped in a single-key envelope (`{"v": <value>}`) so a top-level `null` or bare
 * scalar round-trips unambiguously. Only JSON-friendly results are supported — scalars, `null`,
 * lists, and maps thereof; object-typed results (`EngineObjectData` or node references) are
 * rejected, since the wire format carries no GraphQL type identity.
 */
object FieldValueSerializer {
    private val objectMapper = jacksonObjectMapper()
    private const val ENVELOPE_KEY = "v"

    /** Serializes a field result to enveloped JSON bytes, rejecting non-JSON-friendly values. */
    fun serializeValue(value: Any?): ByteArray {
        rejectNonJsonFriendly(value)
        return objectMapper.writeValueAsBytes(mapOf(ENVELOPE_KEY to value))
    }

    /** Inverse of [serializeValue]. */
    fun deserializeValue(jsonBytes: ByteArray): Any? {
        @Suppress("UNCHECKED_CAST")
        val envelope = objectMapper.readValue(jsonBytes, Map::class.java) as Map<String, Any?>
        return envelope[ENVELOPE_KEY]
    }

    /** Serializes a field-argument map to JSON bytes. */
    fun serializeArguments(arguments: Map<String, Any?>): ByteArray = objectMapper.writeValueAsBytes(arguments)

    /** Inverse of [serializeArguments]; an empty payload deserializes to an empty map. */
    fun deserializeArguments(jsonBytes: ByteArray): Map<String, Any?> {
        if (jsonBytes.isEmpty()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(jsonBytes, Map::class.java) as Map<String, Any?>
    }

    // Walks the value structurally so a nested object slipped inside a list/map is caught too.
    private fun rejectNonJsonFriendly(value: Any?) {
        when (value) {
            null, is String, is Boolean, is Number -> Unit
            is Map<*, *> -> value.values.forEach { rejectNonJsonFriendly(it) }
            is Iterable<*> -> value.forEach { rejectNonJsonFriendly(it) }
            else -> throw UnsupportedOperationException(
                "Remote field resolvers support only JSON-friendly return values " +
                    "(scalars, null, lists, maps); got ${value::class.qualifiedName}."
            )
        }
    }
}
