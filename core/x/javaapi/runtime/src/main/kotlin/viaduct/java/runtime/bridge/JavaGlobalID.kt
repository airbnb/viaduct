package viaduct.java.runtime.bridge

import viaduct.engine.api.NodeReference
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.JavaObjectBase
import viaduct.java.api.reflect.Type
import viaduct.java.api.types.NodeCompositeOutput
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Minimal [GlobalID] backed by a [Type] reference and an internal id string.
 *
 * Java equivalent of Kotlin's [viaduct.api.globalid.GlobalIDImpl]. Used by
 * [SimpleNodeExecutionContext] for node resolver contexts and by [SimpleFieldExecutionContext]
 * for field resolvers that call [globalIDFor].
 */
internal data class JavaGlobalID<T : NodeCompositeOutput>(
    private val type: Type<T>,
    private val internalId: String,
) : GlobalID<T> {
    override fun getType(): Type<T> = type

    override fun getInternalID(): String = internalId
}

/**
 * Returns a [Type] backed by a type name only — used when only the GraphQL type name is known
 * (e.g. when decoding a serialized GlobalID at runtime). The returned type's [Type.getJavaClass]
 * falls back to a generic [NodeObject] class because the concrete GRT class is not always known
 * at the bridge layer.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T : NodeCompositeOutput> typeFromName(name: String): Type<T> =
    object : Type<T> {
        override fun getName(): String = name

        override fun getJavaClass(): Class<out T> = NodeObject::class.java as Class<out T>

        override fun toString(): String = "Type($name)"
    }

/** Creates a typed [GlobalID] from type name and internal id. */
internal fun <T : NodeCompositeOutput> GlobalIDCodec.createGlobalID(
    typeName: String,
    internalID: String,
): GlobalID<T> = JavaGlobalID(type = typeFromName(typeName), internalId = internalID)

/** Creates a typed [GlobalID] from a [Type] and internal id, preserving the concrete GRT class. */
internal fun <T : NodeCompositeOutput> GlobalIDCodec.createGlobalID(
    type: Type<T>,
    internalID: String,
): GlobalID<T> = JavaGlobalID(type = type, internalId = internalID)

/** Serializes a [GlobalID] to its string representation using this codec. */
internal fun <T : NodeCompositeOutput> GlobalIDCodec.serializeGlobalID(globalID: GlobalID<T>): String = serialize(globalID.getType().name, globalID.getInternalID())

/**
 * A minimal [JavaObjectBase] subclass wrapping a [NodeReference].
 *
 * Used by [SimpleFieldExecutionContext.nodeRef] to return a node reference to the engine.
 * [JavaGRTConverter.convertResult] detects this via [JavaObjectBase.getJavaNodeReference] and
 * passes the [NodeReference] directly to the engine instead of converting to
 * [viaduct.engine.api.EngineObjectData.Sync].
 */
internal class JavaNodeRefWrapper(nodeReference: NodeReference) : JavaObjectBase(nodeReference)
