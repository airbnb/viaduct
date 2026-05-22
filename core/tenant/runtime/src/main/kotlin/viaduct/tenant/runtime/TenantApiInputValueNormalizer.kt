package viaduct.tenant.runtime

import kotlin.Enum as KotlinEnum
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InputLikeBase
import viaduct.api.types.GRT
import viaduct.errors.TenantUsageException
import viaduct.service.api.spi.GlobalIDCodec

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
 * recursively repair the inputData map.
 */
internal object TenantApiInputValueNormalizer {
    fun normalizeVariablesForEngine(
        variables: Map<String, Any?>,
        globalIDCodec: GlobalIDCodec,
    ): Map<String, Any?> =
        variables.mapValues { (_, value) ->
            normalizeValueForEngine(value, globalIDCodec)
        }

    fun normalizeValueForEngine(
        value: Any?,
        globalIDCodec: GlobalIDCodec,
    ): Any? =
        when (value) {
            null -> null
            is GlobalID<*> -> globalIDCodec.serialize(value.type.name, value.internalID)
            is InputLikeBase -> value.inputData
            is KotlinEnum<*> -> value.name
            is Map<*, *> -> value.mapValues { (_, nestedValue) -> normalizeValueForEngine(nestedValue, globalIDCodec) }
            is Iterable<*> -> value.map { normalizeValueForEngine(it, globalIDCodec) }
            is Array<*> -> value.map { normalizeValueForEngine(it, globalIDCodec) }
            is GRT -> throw TenantUsageException(
                "Unsupported Tenant API value in engine variables: ${value.javaClass.name}. " +
                    "Only input GRTs, enum GRTs, GlobalID values, maps, lists, arrays, and scalars are supported."
            )
            else -> value
        }
}
