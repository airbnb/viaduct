package viaduct.remote

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData

/**
 * JSON codec for the engine values this transport carries: [EngineObjectData] payloads (node results,
 * required-selection-set object and query values, callback results) and field-resolver return values.
 *
 * Every JSON *object* on the wire is a single-key envelope, because a bare JSON object is ambiguous —
 * it could be an [EngineObjectData] or a map-valued custom scalar. Primitives, nulls and lists are
 * unambiguous and stay bare, so scalars pay no encoding overhead:
 *
 * | value                     | JSON                                              |
 * |---------------------------|---------------------------------------------------|
 * | [EngineObjectData.Sync]   | `{"o":{"t":"<Type>","f":{<selection>: <value>}}}` |
 * | [NodeReference]           | `{"r":{"t":"<Type>","id":"<globalId>"}}`          |
 * | map-valued scalar         | `{"s":{ …opaque JSON… }}`                         |
 * | list                      | `[<value>, …]`                                    |
 * | string / number / boolean | bare JSON scalar                                  |
 * | null                      | bare JSON `null`                                  |
 * | unset selection           | key absent from `"f"`                             |
 *
 * Because each object carries `"t"`, type identity survives at every depth: nested objects are rebuilt
 * against the receiver's real schema type rather than a placeholder, so consumers that inspect type
 * identity (GRT reflection, interface/union membership, Classic interop) work below the root.
 * Compatible schemas on both sides are still required — an unknown type name is rejected, but a
 * *changed* same-named type is not detected.
 *
 * The payload root is `[<version>, <value>]`. A JSON array root is structurally impossible in the
 * pre-versioned format, which always wrote an object, so a build mismatch fails loudly in both
 * directions instead of silently decoding into a wrong-but-plausible value. That covers the engine
 * values encoded here; the plain-JSON argument and variable maps in [FieldValueSerializer] carry no
 * version of their own.
 */
object EngineObjectDataSerializer {
    private val objectMapper = jacksonObjectMapper()

    /** Bump when the encoding below changes incompatibly; readers reject a version they don't know. */
    private const val WIRE_VERSION = 1

    // Encode depth is resolver-controlled, decode depth peer-controlled. A StackOverflowError is an
    // Error and escapes the per-item isolation catches, so the walks below are capped instead.
    private const val MAX_DEPTH = 100

    private const val OBJECT = "o"
    private const val NODE_REF = "r"
    private const val SCALAR_MAP = "s"
    private const val TYPE = "t"
    private const val FIELDS = "f"
    private const val ID = "id"

    /** Serializes a fully-resolved [EngineObjectData] payload. */
    fun serialize(data: EngineObjectData): ByteArray {
        // NodeUnbatchedResolverExecutorImpl.unwrapNodeResolverResult already rejects unresolved
        // results upstream of here.
        val sync = data as? EngineObjectData.Sync
            ?: throw UnsupportedOperationException(
                "Invariant violation: received an unresolved EngineObjectData (type '${data.type.name}') " +
                    "to serialize. Node resolvers must return fully resolved data (via a GRT builder), " +
                    "not an unresolved NodeReference or RootFieldReference."
            )
        return wrap(encodeValue(sync, allowNodeReference = false))
    }

    /**
     * Deserializes an [EngineObjectData] payload against [schema].
     *
     * [expectedTypeName] is the type the receiver already knows the payload must have: a node's type,
     * a field's parent type, or a selection set's type. The wire's name is asserted against it.
     */
    fun deserialize(
        jsonBytes: ByteArray,
        schema: GraphQLSchema,
        expectedTypeName: String
    ): EngineObjectData.Sync {
        val decoded = decodeValue(unwrap(jsonBytes), schema, context = null)
        val eod = decoded as? EngineObjectData.Sync
            ?: throw UnsupportedOperationException(
                "Expected an EngineObjectData payload for type '$expectedTypeName', got ${describe(decoded)}"
            )
        if (eod.type.name != expectedTypeName) {
            throw UnsupportedOperationException(
                "Remote payload declares type '${eod.type.name}' but this receiver expected '$expectedTypeName'"
            )
        }
        return eod
    }

    /**
     * Serializes a field-resolver return value. Unlike an [EngineObjectData] payload this may be any
     * `Any?` a resolver returned, including an unresolved [NodeReference].
     */
    fun serializeFieldValue(value: Any?): ByteArray = wrap(encodeValue(value, allowNodeReference = true))

    /** Inverse of [serializeFieldValue]; rebuilds references and objects against [context]'s live schema. */
    fun deserializeFieldValue(
        jsonBytes: ByteArray,
        context: EngineExecutionContext
    ): Any? = decodeValue(unwrap(jsonBytes), context.fullSchema.schema, context)

    /**
     * Wraps an encoded value in the versioned payload root. Visible for tests that hand-build
     * payloads.
     */
    internal fun wrap(encoded: Any?): ByteArray = objectMapper.writeValueAsBytes(listOf(WIRE_VERSION, encoded))

    /** Inverse of [wrap]. Rejects an unversioned or unknown-version payload rather than guessing. */
    private fun unwrap(jsonBytes: ByteArray): Any? {
        if (jsonBytes.isEmpty()) {
            throw UnsupportedOperationException("Remote payload is empty; expected a versioned [version, value] root")
        }
        val versioned = objectMapper.readValue(jsonBytes, Any::class.java) as? List<*>
            ?: throw UnsupportedOperationException(
                "Remote payload root is not a versioned [version, value] array — the peer is running an " +
                    "incompatible build of this transport"
            )
        if (versioned.size != 2) {
            throw UnsupportedOperationException(
                "Remote payload root has ${versioned.size} element(s); expected exactly [version, value]"
            )
        }
        // Int, not Number: a non-integral version must not truncate into a match.
        if (versioned[0] != WIRE_VERSION) {
            throw UnsupportedOperationException(
                "Unsupported remote payload version ${versioned[0]}; this build speaks version $WIRE_VERSION"
            )
        }
        return versioned[1]
    }

    /**
     * Encodes one engine value.
     *
     * [allowNodeReference] applies to this position and to list elements below it, never to an
     * object's fields. Only field-resolver return values set it.
     */
    private fun encodeValue(
        value: Any?,
        allowNodeReference: Boolean,
        depth: Int = 0
    ): Any? {
        if (depth > MAX_DEPTH) {
            throw UnsupportedOperationException("Remote value nests deeper than $MAX_DEPTH levels")
        }
        return when (value) {
            null -> null
            // NodeReference is also an EngineObjectData, so it must be matched first.
            is NodeReference -> {
                if (!allowNodeReference) {
                    throw UnsupportedOperationException(
                        "Cannot serialize a nested NodeReference (type '${value.type.name}'); " +
                            "resolve the reference before returning it."
                    )
                }
                mapOf(NODE_REF to mapOf(TYPE to value.type.name, ID to value.id))
            }
            is EngineObjectData.Sync ->
                mapOf(OBJECT to mapOf(TYPE to value.type.name, FIELDS to encodeFields(value, depth)))
            // Awaiting any other unresolved reference here (e.g. a RootFieldReference) would block
            // until the request deadline.
            is EngineObjectData -> throw UnsupportedOperationException(
                "Cannot serialize an unresolved EngineObjectData (type '${value.type.name}'); " +
                    "resolve it before returning it."
            )
            // A map ships as an opaque JSON leaf: its contents are never encoded, so they must already
            // be JSON-friendly.
            is Map<*, *> -> {
                rejectNonJsonFriendly(value, depth)
                mapOf(SCALAR_MAP to value)
            }
            // List only: a Set or Sequence would be reshaped into a list on the wire.
            is List<*> -> value.map { encodeValue(it, allowNodeReference, depth + 1) }
            // An enum reaches here when the value came from OER slots rather than a GRT builder
            // (SyncEngineObjectDataFactory returns enums as-is). It decodes as a String, which
            // EODBuilderWrapper.unwrapEnum accepts.
            is Enum<*> -> value.name
            is String, is Boolean, is Number -> value
            else -> throw UnsupportedOperationException(
                "Cannot serialize a remote value of type ${value::class.qualifiedName}; supported kinds are " +
                    "scalars, enums, null, resolved objects, node references, and lists thereof."
            )
        }
    }

    /**
     * Inverse of [encodeValue]. [context] is non-null only for field-value payloads, and rebuilds node
     * references.
     */
    private fun decodeValue(
        encoded: Any?,
        schema: GraphQLSchema,
        context: EngineExecutionContext?,
        depth: Int = 0
    ): Any? {
        if (depth > MAX_DEPTH) {
            throw UnsupportedOperationException("Remote payload nests deeper than $MAX_DEPTH levels")
        }
        return when (encoded) {
            null, is String, is Boolean, is Number -> encoded
            is List<*> -> encoded.map { decodeValue(it, schema, context, depth + 1) }
            is Map<*, *> -> decodeEnvelope(encoded, schema, context, depth)
            else -> throw UnsupportedOperationException("Unexpected remote value ${describe(encoded)}")
        }
    }

    private fun encodeFields(
        data: EngineObjectData.Sync,
        depth: Int
    ): Map<String, Any?> {
        val fields = mutableMapOf<String, Any?>()
        for (selection in data.getSelections()) {
            fields[selection] = encodeValue(data.getOrNull(selection), allowNodeReference = false, depth = depth + 1)
        }
        return fields
    }

    private fun decodeEnvelope(
        envelope: Map<*, *>,
        schema: GraphQLSchema,
        context: EngineExecutionContext?,
        depth: Int
    ): Any? {
        val entry = envelope.entries.singleOrNull()
            ?: throw UnsupportedOperationException(
                "Remote value envelope must have exactly one key (one of '$OBJECT'/'$NODE_REF'/'$SCALAR_MAP'), " +
                    "got ${envelope.keys}"
            )
        return when (entry.key) {
            SCALAR_MAP -> entry.value
            OBJECT -> decodeObject(bodyOf(entry.value), schema, context, depth)
            NODE_REF -> decodeNodeReference(bodyOf(entry.value), schema, context)
            else -> throw UnsupportedOperationException(
                "Unknown remote value envelope key '${entry.key}'; expected one of '$OBJECT'/'$NODE_REF'/'$SCALAR_MAP'"
            )
        }
    }

    private fun decodeObject(
        body: Map<*, *>,
        schema: GraphQLSchema,
        context: EngineExecutionContext?,
        depth: Int
    ): EngineObjectData.Sync {
        val type = wireType(body, schema)
        val fields = body[FIELDS] as? Map<*, *>
            ?: throw UnsupportedOperationException("Remote object '${type.name}' is missing its field map")
        // Builder.build() copies its accumulator; this constructs the map once.
        val decoded = LinkedHashMap<String, Any?>(fields.size * 4 / 3 + 1)
        for ((selection, value) in fields) {
            decoded[selection as String] = decodeValue(value, schema, context, depth + 1)
        }
        return ResolvedEngineObjectData(type, decoded)
    }

    private fun decodeNodeReference(
        body: Map<*, *>,
        schema: GraphQLSchema,
        context: EngineExecutionContext?
    ): NodeReference {
        val type = wireType(body, schema)
        val id = body[ID] as? String
            ?: throw UnsupportedOperationException("Remote node reference for '${type.name}' is missing its 'id'")
        val ctx = context
            ?: throw UnsupportedOperationException(
                "Remote payload carries a node reference ('${type.name}'), which only a field-value payload may do"
            )
        return ctx.createNodeReference(id, type)
    }

    private fun bodyOf(value: Any?): Map<*, *> =
        value as? Map<*, *>
            ?: throw UnsupportedOperationException("Remote value envelope body is ${describe(value)}, expected an object")

    /** Resolves the concrete type name recorded on the wire against the receiver's live schema. */
    private fun wireType(
        body: Map<*, *>,
        schema: GraphQLSchema
    ): GraphQLObjectType {
        val typeName = body[TYPE] as? String
            ?: throw UnsupportedOperationException("Remote value envelope is missing its type name")
        // getObjectType asserts when the name resolves to a non-object type, so getType is used here.
        return schema.getType(typeName) as? GraphQLObjectType
            ?: throw UnsupportedOperationException(
                "Remote payload references GraphQL type '$typeName', which this schema does not define as " +
                    "an object type — the peer's schema is incompatible with this one"
            )
    }

    // Contents of a `"s"` leaf are never decoded, so anything not JSON-friendly is rejected here.
    private fun rejectNonJsonFriendly(
        value: Any?,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) {
            throw UnsupportedOperationException("Remote value nests deeper than $MAX_DEPTH levels")
        }
        when (value) {
            null, is String, is Boolean, is Number -> Unit
            // Jackson writes a non-String key as its toString(), which decodes as a garbage string.
            is Map<*, *> -> value.forEach { (key, entryValue) ->
                if (key !is String) {
                    throw UnsupportedOperationException(
                        "Cannot serialize a map with a non-String key (${key?.let { it::class.qualifiedName }}); " +
                            "JSON object keys must be strings."
                    )
                }
                rejectNonJsonFriendly(entryValue, depth + 1)
            }
            is List<*> -> value.forEach { rejectNonJsonFriendly(it, depth + 1) }
            else -> throw UnsupportedOperationException(
                "Cannot serialize a map/list containing ${value::class.qualifiedName}; map and list-leaf " +
                    "contents must be JSON-friendly (scalars, null, lists, maps)."
            )
        }
    }

    private fun describe(value: Any?): String = if (value == null) "null" else "a ${value::class.simpleName}"
}
