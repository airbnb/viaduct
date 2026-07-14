package viaduct.tenant.runtime

import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InputLikeBase
import viaduct.api.types.GRT
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.tenant.runtime.support.InputValueNormalizerCore
import viaduct.tenant.runtime.support.InputValueNormalizerCore.InputValueAdapter

/**
 * Normalizes Tenant API input values before they cross back into the Viaduct engine.
 *
 * This is deliberately not GraphQL variable coercion. GraphQL Java still validates and coerces
 * these maps against schema argument and variable definitions after this step. This helper only
 * removes Tenant API input wrapper objects that GraphQL Java cannot inspect directly, such as
 * typed [GlobalID] values and enum GRTs in raw variable maps.
 *
 * [InputLikeBase.inputData] is expected to already contain engine-shaped values. Generated Kotlin
 * input builders enforce that by converting tenant-facing values through the GRT and engine
 * converters before storing them. This normalizer only removes the outer input wrapper; it does not
 * recursively repair the inputData map. The shared traversal lives in [InputValueNormalizerCore];
 * the Kotlin-specific leaf handling is supplied by [KotlinInputValueAdapter].
 */
internal object TenantApiInputValueNormalizer {
    fun normalizeVariablesForEngine(
        variables: Map<String, Any?>,
        globalIDCodec: GlobalIDCodec,
    ): Map<String, Any?> = InputValueNormalizerCore.normalizeVariablesForEngine(variables, globalIDCodec, KotlinInputValueAdapter)

    fun normalizeValueForEngine(
        value: Any?,
        globalIDCodec: GlobalIDCodec,
    ): Any? = InputValueNormalizerCore.normalizeValueForEngine(value, globalIDCodec, KotlinInputValueAdapter)
}

private object KotlinInputValueAdapter : InputValueAdapter {
    override fun globalIdPartsOrNull(value: Any?): Pair<String, String>? = (value as? GlobalID<*>)?.let { it.type.name to it.internalID }

    // Kotlin input builders store engine-shaped values, so the outer wrapper is removed without
    // recursing into its inputData.
    override fun inputDataOrNull(value: Any?): Map<String, Any?>? = (value as? InputLikeBase)?.inputData

    override val recurseIntoInputData: Boolean = false

    override fun unsupportedGrtMessageOrNull(value: Any?): String? =
        if (value is GRT) {
            "Unsupported Tenant API value in engine variables: ${value.javaClass.name}. " +
                "Only input GRTs, enum GRTs, GlobalID values, maps, lists, arrays, and scalars are supported."
        } else {
            null
        }
}
