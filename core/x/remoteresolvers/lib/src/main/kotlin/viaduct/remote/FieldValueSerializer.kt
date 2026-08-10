package viaduct.remote

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import viaduct.engine.api.EngineExecutionContext

/**
 * The field path's view of the wire: field-resolver return values plus the argument and variable maps
 * that travel alongside them.
 *
 * Return values share the self-describing encoding in [EngineObjectDataSerializer], which records each
 * object's concrete GraphQL type — the only difference from an [viaduct.engine.api.EngineObjectData]
 * payload is that a field value may carry an unresolved [viaduct.engine.api.NodeReference], which the
 * engine side rebuilds against its live schema.
 *
 * Argument and variable maps are plain JSON: they hold only input-coercible values, never engine
 * objects, so they need neither type tagging nor versioning.
 */
object FieldValueSerializer {
    private val objectMapper = jacksonObjectMapper()

    /** Serializes a field result; throws for genuinely non-serializable values. */
    fun serializeValue(value: Any?): ByteArray = EngineObjectDataSerializer.serializeFieldValue(value)

    /** Inverse of [serializeValue]; rebuilds references and objects against [context]'s live schema. */
    fun deserializeValue(
        jsonBytes: ByteArray,
        context: EngineExecutionContext
    ): Any? = EngineObjectDataSerializer.deserializeFieldValue(jsonBytes, context)

    /** Serializes a field-argument or selection-set-variable map to JSON bytes. */
    fun serializeArguments(arguments: Map<String, Any?>): ByteArray = objectMapper.writeValueAsBytes(arguments)

    /** Inverse of [serializeArguments]; an empty payload deserializes to an empty map. */
    fun deserializeArguments(jsonBytes: ByteArray): Map<String, Any?> {
        if (jsonBytes.isEmpty()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(jsonBytes, Map::class.java) as Map<String, Any?>
    }
}
