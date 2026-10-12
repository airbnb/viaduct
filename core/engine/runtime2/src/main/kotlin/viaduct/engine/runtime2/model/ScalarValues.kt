package viaduct.engine.runtime2.model

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** Native scalar leaves admitted to the result domain. */
internal fun Any.isNativeScalarResult(): Boolean = nativeScalarTypeNameOrNull() != null

/** The GraphQL scalar represented by this canonical result leaf, if any. */
internal fun Any.scalarResultTypeNameOrNull(): String? =
    when (this) {
        is IDEngineResult -> "ID"
        is JSONEngineResult -> "JSON"
        is BackingDataEngineResult -> "BackingData"
        else -> nativeScalarTypeNameOrNull()
    }

/** Extracts a scalar result's resolver-visible value without serializing it. */
internal fun EngineResult.scalarResultValue(): EngineOutputData =
    when (this) {
        is IDEngineResult -> value
        is JSONEngineResult -> value
        is BackingDataEngineResult -> value
        else -> {
            check(nativeScalarTypeNameOrNull() != null) { "Unexpected scalar engine result: $this" }
            this
        }
    }

/** JVM representations shared by scalar input, output, and result domains. */
private fun Any.nativeScalarTypeNameOrNull(): String? =
    when (this) {
        is Int -> "Int"
        is Double -> "Float".takeIf { isFinite() }
        is String -> "String"
        is Boolean -> "Boolean"
        is Byte -> "Byte"
        is Short -> "Short"
        is Long -> "Long"
        is BigInteger -> "BigInteger"
        is BigDecimal -> "BigDecimal"
        is LocalDate -> "Date"
        is Instant -> "DateTime"
        else -> null
    }

/** Membership in a scalar's already-coerced input domain, independent of source schema wiring. */
internal fun Any.conformsToScalarInput(name: String): Boolean =
    when (name) {
        "ID" -> this is String
        "JSON" -> isJsonValue()
        else -> nativeScalarTypeNameOrNull() == name
    }

/** BackingData and JSON admit opaque output values. */
internal fun Any.conformsToScalarOutput(name: String): Boolean =
    when (name) {
        "BackingData", "JSON" -> true
        else -> conformsToScalarInput(name)
    }

internal fun Any.toScalarInput(name: String): EngineSimpleData {
    val value = if (name == "DateTime" && this is OffsetDateTime) toInstant() else this
    if (!value.conformsToScalarInput(name)) throw ClassCastException("Value does not conform to scalar $name")
    return if (name == "JSON") requireNotNull(value.copyJsonValue()) else value
}

private fun Any?.isJsonValue(): Boolean =
    when (this) {
        null, is String, is Boolean, is Number -> true
        is List<*> -> all { it.isJsonValue() }
        is Map<*, *> -> keys.all { it is String } && values.all { it.isJsonValue() }
        else -> false
    }

internal fun Any?.copyJsonValue(): Any? =
    when (this) {
        is List<*> -> map { it.copyJsonValue() }
        is Map<*, *> -> entries.associate { (key, value) -> key to value.copyJsonValue() }
        else -> this
    }
