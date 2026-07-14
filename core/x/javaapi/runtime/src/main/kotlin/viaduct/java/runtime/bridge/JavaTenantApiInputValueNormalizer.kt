package viaduct.java.runtime.bridge

import viaduct.engine.api.EngineExecutionContext
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.InputBase
import viaduct.java.api.types.GRT
import viaduct.tenant.runtime.support.InputValueNormalizerCore
import viaduct.tenant.runtime.support.InputValueNormalizerCore.InputValueAdapter

/**
 * Normalizes Java Tenant API input values before they cross back into the Viaduct engine.
 *
 * This keeps Java ctx.query/ctx.mutation and Java VariablesProvider behavior aligned with the
 * Kotlin Tenant API. It is not GraphQL variable coercion; it only unwraps generated Tenant API
 * input objects into values the engine and GraphQL Java can subsequently coerce against the schema.
 * Unlike modern Kotlin [viaduct.api.internal.InputLikeBase.inputData], Java [InputBase.inputData]
 * stores values directly from generated builders, so it can still contain nested Tenant API
 * wrappers and must be normalized recursively. The shared traversal lives in
 * [InputValueNormalizerCore]; the Java-specific leaf handling is supplied by [JavaInputValueAdapter].
 */
internal object JavaTenantApiInputValueNormalizer {
    fun normalizeVariablesForEngine(
        variables: Map<String, Any?>,
        context: EngineExecutionContext,
    ): Map<String, Any?> = InputValueNormalizerCore.normalizeVariablesForEngine(variables, context.globalIDCodec, JavaInputValueAdapter)

    fun normalizeValueForEngine(
        value: Any?,
        context: EngineExecutionContext,
    ): Any? = InputValueNormalizerCore.normalizeValueForEngine(value, context.globalIDCodec, JavaInputValueAdapter)
}

private object JavaInputValueAdapter : InputValueAdapter {
    override fun globalIdPartsOrNull(value: Any?): Pair<String, String>? = (value as? GlobalID<*>)?.let { it.type.name to it.internalID }

    // Java input builders store raw tenant values, so the unwrapped inputData must be normalized
    // recursively.
    override fun inputDataOrNull(value: Any?): Map<String, Any?>? = (value as? InputBase)?.inputData

    override val recurseIntoInputData: Boolean = true

    override fun unsupportedGrtMessageOrNull(value: Any?): String? =
        if (value is GRT) {
            "Unsupported Java Tenant API value in engine variables: ${value.javaClass.name}. " +
                "Only input GRTs, enum GRTs, GlobalID values, maps, lists, arrays, and scalars are supported."
        } else {
            null
        }
}
