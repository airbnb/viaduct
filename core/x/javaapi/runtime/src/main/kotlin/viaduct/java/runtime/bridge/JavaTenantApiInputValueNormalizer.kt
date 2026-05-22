package viaduct.java.runtime.bridge

import viaduct.engine.api.EngineExecutionContext
import viaduct.errors.TenantUsageException
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.InputBase
import viaduct.java.api.types.GRT

/**
 * Normalizes Java Tenant API input values before they cross back into the Viaduct engine.
 *
 * This keeps Java ctx.query/ctx.mutation and Java VariablesProvider behavior aligned with the
 * Kotlin Tenant API. It is not GraphQL variable coercion; it only unwraps generated Tenant API
 * input objects into values the engine and GraphQL Java can subsequently coerce against the schema.
 * Unlike modern Kotlin [viaduct.api.internal.InputLikeBase.inputData], Java [InputBase.inputData]
 * stores values directly from generated builders, so it can still contain nested Tenant API
 * wrappers and must be normalized recursively.
 */
internal object JavaTenantApiInputValueNormalizer {
    fun normalizeVariablesForEngine(
        variables: Map<String, Any?>,
        context: EngineExecutionContext,
    ): Map<String, Any?> =
        variables.mapValues { (_, value) ->
            normalizeValueForEngine(value, context)
        }

    fun normalizeValueForEngine(
        value: Any?,
        context: EngineExecutionContext,
    ): Any? =
        when (value) {
            null -> null
            is GlobalID<*> -> context.globalIDCodec.serialize(value.type.name, value.internalID)
            is InputBase -> value.inputData.mapValues { (_, nestedValue) -> normalizeValueForEngine(nestedValue, context) }
            is Enum<*> -> value.name
            is Map<*, *> -> value.mapValues { (_, nestedValue) -> normalizeValueForEngine(nestedValue, context) }
            is Iterable<*> -> value.map { normalizeValueForEngine(it, context) }
            is Array<*> -> value.map { normalizeValueForEngine(it, context) }
            is GRT -> throw TenantUsageException(
                "Unsupported Java Tenant API value in engine variables: ${value.javaClass.name}. " +
                    "Only input GRTs, enum GRTs, GlobalID values, maps, lists, arrays, and scalars are supported."
            )
            else -> value
        }
}
