package viaduct.tenant.runtime.support

import viaduct.apiannotations.InternalApi
import viaduct.errors.TenantUsageException
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Language-neutral core for normalizing Tenant API input values before they cross back into the
 * Viaduct engine.
 *
 * This is deliberately NOT GraphQL variable coercion. GraphQL Java still validates and coerces these
 * maps against schema argument and variable definitions after this step. This helper only removes
 * Tenant API input wrapper objects that GraphQL Java cannot inspect directly — typed GlobalID values,
 * generated input wrappers, and enum GRTs in raw variable maps.
 *
 * Both tenant runtimes ran byte-identical copies of this recursion. The only genuine differences are
 * the language-specific leaf types (`viaduct.api.internal.InputLikeBase` vs
 * `viaduct.java.api.internal.InputBase`; the two `GlobalID` flavors; the two `GRT` markers) and one
 * behavioral nuance: the Kotlin generated input builders already store engine-shaped values in
 * `inputData`, so the Kotlin side does NOT recurse into an unwrapped input, while the Java side
 * stores raw tenant values and MUST recurse. Those differences are isolated behind [InputValueAdapter];
 * this object owns the shared traversal.
 *
 * Internal support surface: consumed only by the Kotlin and Java tenant runtimes, not by tenants.
 */
@InternalApi
object InputValueNormalizerCore {
    /**
     * Adapter supplying the per-language behavior the shared traversal cannot know:
     * how to recognize/unwrap a typed GlobalID and a generated input wrapper, whether to recurse into
     * an unwrapped input map, and how to reject an unsupported GRT.
     */
    interface InputValueAdapter {
        /**
         * If [value] is a typed GlobalID, return its (typeName, internalID); otherwise null.
         * The core serializes it via the [GlobalIDCodec].
         */
        fun globalIdPartsOrNull(value: Any?): Pair<String, String>?

        /**
         * If [value] is a generated input wrapper, return its backing `inputData` map; otherwise null.
         */
        fun inputDataOrNull(value: Any?): Map<String, Any?>?

        /**
         * Whether the values of an unwrapped [inputDataOrNull] map should themselves be normalized
         * recursively. Kotlin returns false (already engine-shaped); Java returns true.
         */
        val recurseIntoInputData: Boolean

        /**
         * If [value] is a Tenant API GRT that is not a supported input/enum/GlobalID, return the
         * error message to raise; otherwise null. The core throws [TenantUsageException] with it.
         */
        fun unsupportedGrtMessageOrNull(value: Any?): String?
    }

    /** Normalize every value in a raw variable map. */
    fun normalizeVariablesForEngine(
        variables: Map<String, Any?>,
        globalIDCodec: GlobalIDCodec,
        adapter: InputValueAdapter,
    ): Map<String, Any?> = variables.mapValues { (_, value) -> normalizeValueForEngine(value, globalIDCodec, adapter) }

    /**
     * Normalize a single value. Match order is significant and preserved from the original
     * per-language implementations: GlobalID, input wrapper, enum, map, iterable, array, unsupported
     * GRT, then pass-through.
     */
    fun normalizeValueForEngine(
        value: Any?,
        globalIDCodec: GlobalIDCodec,
        adapter: InputValueAdapter,
    ): Any? {
        if (value == null) return null

        adapter.globalIdPartsOrNull(value)?.let { (typeName, internalID) ->
            return globalIDCodec.serialize(typeName, internalID)
        }

        adapter.inputDataOrNull(value)?.let { inputData ->
            return if (adapter.recurseIntoInputData) {
                inputData.mapValues { (_, nested) -> normalizeValueForEngine(nested, globalIDCodec, adapter) }
            } else {
                inputData
            }
        }

        return when (value) {
            is Enum<*> -> value.name
            is Map<*, *> -> value.mapValues { (_, nested) -> normalizeValueForEngine(nested, globalIDCodec, adapter) }
            is Iterable<*> -> value.map { normalizeValueForEngine(it, globalIDCodec, adapter) }
            is Array<*> -> value.map { normalizeValueForEngine(it, globalIDCodec, adapter) }
            else -> {
                adapter.unsupportedGrtMessageOrNull(value)?.let { throw TenantUsageException(it) }
                value
            }
        }
    }
}
