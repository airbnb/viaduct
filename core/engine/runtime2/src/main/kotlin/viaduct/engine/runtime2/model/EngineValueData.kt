package viaduct.engine.runtime2.model

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.schema.idl.ScalarInfo
import java.util.Locale
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.Scalars
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.toGraphQLJavaValue

/**
 * A canonical scalar input value (including Viaduct scalars), or an enum name. String represents GraphQL String, ID, and enum values;
 * the expected schema type disambiguates them.
 */
typealias EngineSimpleData = Any

/** A recursively validated list of nullable engine input values. */
typealias EngineInputListData = List<EngineInputData?>

/** A recursively validated input-object field map. */
typealias EngineInputObjectData = Map<String, EngineInputData?>

/** [EngineSimpleData], [EngineInputListData], or [EngineInputObjectData]. */
typealias EngineInputData = Any

/**
 * An optional, fully coerced semantic schema default.
 *
 * [Absent] means that no default is declared. [Present.value] may be null, denoting an explicit
 * GraphQL null; absence and explicit null are distinct. When attached to a field or argument,
 * [Present] is valid for its declaring [ViaductSchema.TypeExpr]. Default values use structural equality.
 */
sealed interface CoercedDefaultValue {
    data object Absent : CoercedDefaultValue

    sealed interface Present : CoercedDefaultValue {
        val value: EngineInputData?
    }

    companion object {
        fun of(value: EngineInputData?): Present = PresentCoercedDefaultValueImpl(value)
    }
}

private data class PresentCoercedDefaultValueImpl(
    override val value: EngineInputData?,
) : CoercedDefaultValue.Present

/**
 * A schema-directed scalar value, [EngineObjectData.Sync], or [EngineOutputListData].
 * JSON objects and arrays are scalar values; BackingData retains its opaque JVM value.
 *
 * String represents GraphQL String, ID, and enum values; the expected schema type disambiguates
 * them. [EngineErrorData] is additionally admitted to the broad output domain. This union is
 * equality-heterogeneous: equality is undefined until an operation narrows values to a homogeneous
 * subset such as [EngineSimpleData].
 */
typealias EngineOutputData = Any

/** The structured outcome of asynchronously producing one resolver Query-fragment input. */
sealed interface EngineObjectOrErrorData {
    /** A successfully materialized Query-fragment object. */
    sealed interface Success : EngineObjectOrErrorData {
        val value: EngineObjectData.Sync
    }

    /** An error that prevented Query-fragment materialization. */
    sealed interface Error : EngineObjectOrErrorData {
        val error: EngineErrorData
    }

    companion object {
        fun of(value: EngineObjectData.Sync): Success = SuccessEngineObjectOrErrorDataImpl(value)

        fun of(error: EngineErrorData): Error = ErrorEngineObjectOrErrorDataImpl(error)
    }
}

private data class SuccessEngineObjectOrErrorDataImpl(
    override val value: EngineObjectData.Sync,
) : EngineObjectOrErrorData.Success

private data class ErrorEngineObjectOrErrorDataImpl(
    override val error: EngineErrorData,
) : EngineObjectOrErrorData.Error

/**
 * [EngineOutputData] or [RootFieldReferenceData].
 *
 * This is the field-resolver codomain. A root-field reference is a symbolic resolver instruction
 * with grounded, variable-free arguments, not engine data supplied as resolver input. List and
 * object positions within resolver output may recursively contain [ResolverOutputData].
 */
typealias ResolverOutputData = Any

/** A recursively validated list of nullable engine output values. */
typealias EngineOutputListData = List<EngineOutputData?>

/**
 * The resolver-output error variant.
 *
 * This belongs only to the broad [EngineOutputData] domain. It is distinct from
 * [ErrorEngineResult] and argument-resolution failure. Instances use reference equality and will
 * carry complete causal and attributional metadata.
 */
sealed interface EngineErrorData {
    /** The executor failure represented by this error, when one crossed the adapter boundary. */
    val cause: Throwable?

    companion object {
        /** Creates an output error with no metadata. */
        fun of(): EngineErrorData = EngineErrorDataImpl(null)

        /** Creates an output error retaining the exact executor [cause]. */
        fun of(cause: Throwable): EngineErrorData = EngineErrorDataImpl(cause)
    }
}

private class EngineErrorDataImpl(
    override val cause: Throwable?,
) : EngineErrorData

/** Converts a simple engine result to production-compatible engine input data. */
fun EngineResult.toEngineSimpleData(expectedType: ViaductSchema.SimpleTypeDef): EngineSimpleData =
    toEngineOutputData(expectedType).let { value ->
        when (expectedType) {
            is ViaductSchema.Scalar -> value.toScalarInput(expectedType.name)
            else -> value
        }
    }

/** Recursively copies [value] as [EngineInputData] conforming to [expectedType]. */
internal fun toEngineInputData(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    value: EngineInputData?,
): EngineInputData? {
    if (value == null) {
        if (!expectedType.isNullable) throw ClassCastException()
        return null
    }

    val elementType = expectedType.unwrapList()
    return if (elementType == null) {
        toEngineNamedInputData(expectedType.baseTypeDef, value)
    } else {
        val elements = value.cast<EngineInputListData>()
        toEngineInputListData(expectedType, elements)
    }
}

/** Converts [value] to canonical [EngineSimpleData] conforming to [expectedType]. */
internal fun toEngineSimpleData(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.SimpleTypeDef>,
    value: EngineSimpleData?,
): EngineSimpleData? {
    if (value == null) {
        if (!expectedType.isNullable) throw ClassCastException()
        return null
    }
    if (expectedType.isList) throw ClassCastException()
    return toEngineSimpleData(expectedType.baseTypeDef, value)
}

/** Recursively copies [value] as canonical list data conforming to [expectedType]. */
fun toEngineInputListData(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    value: EngineInputListData,
): EngineInputListData {
    val elementType = expectedType.unwrapList() ?: throw ClassCastException()
    return value.map { element ->
        toEngineInputData(elementType, element)
    }
}

/** Recursively copies [value] as canonical input-object data conforming to [expectedType]. */
internal fun toEngineInputObjectData(
    expectedType: ViaductSchema.Input,
    value: EngineInputObjectData,
): EngineInputObjectData = toEngineInputFields(expectedType, value)

private fun toEngineNamedInputData(
    expectedType: ViaductSchema.InputTypeDef,
    value: EngineInputData,
): EngineInputData =
    when (expectedType) {
        is ViaductSchema.SimpleTypeDef -> toEngineSimpleData(expectedType, value)
        is ViaductSchema.Input -> {
            val fields = value as? Map<*, *> ?: throw ClassCastException()
            toEngineInputObjectData(expectedType, fields.toStringKeyedMap())
        }
        else -> error("Unsupported input type: ${expectedType.name}")
    }

private fun toEngineSimpleData(
    expectedType: ViaductSchema.SimpleTypeDef,
    value: EngineSimpleData,
): EngineSimpleData =
    when (expectedType) {
        is ViaductSchema.Scalar -> value.toScalarInput(expectedType.name)
        is ViaductSchema.Enum ->
            value.cast<String>().also {
                if (expectedType.value(it) == null) throw ClassCastException()
            }
        else -> error("Unsupported simple type: ${expectedType.name}")
    }

private inline fun <reified T> Any.cast(): T = this as? T ?: throw ClassCastException()

/** Converts simple resolver output to the result representation selected by [expectedType]. */
fun EngineOutputData.toEngineResult(expectedType: ViaductSchema.SimpleTypeDef): EngineResult =
    when (expectedType) {
        is ViaductSchema.Scalar ->
            when (expectedType.name) {
                "ID" -> IDEngineResult.of(cast())
                "JSON" -> JSONEngineResult.of(this)
                "BackingData" -> BackingDataEngineResult.of(this)
                else -> toScalarInput(expectedType.name)
            }
        is ViaductSchema.Enum -> expectedType.requireValue(cast())
        else -> error("Unsupported simple type: ${expectedType.name}")
    }

/** Converts a simple engine result to production-compatible resolver output. */
fun EngineResult.toEngineOutputData(expectedType: ViaductSchema.SimpleTypeDef): EngineOutputData =
    when (expectedType) {
        is ViaductSchema.Scalar -> {
            if (scalarResultTypeNameOrNull() != expectedType.name) throw ClassCastException()
            scalarResultValue()
        }
        is ViaductSchema.Enum -> {
            val value = cast<ViaductSchema.EnumValue>()
            if (value.containingDef != expectedType) throw ClassCastException()
            value.name
        }
        else -> error("Unsupported simple type: ${expectedType.name}")
    }

private fun toEngineInputFields(
    expectedType: ViaductSchema.Input,
    fields: EngineInputObjectData,
): EngineInputObjectData {
    val supplied =
        fields.mapValues { (name, value) ->
            val field = expectedType.field(name) ?: throw ClassCastException()
            toEngineInputData(field.inputType, value)
        }

    return buildMap {
        expectedType.fields.forEach { field ->
            val defaultValue = field.coercedDefaultValue()
            if (defaultValue is CoercedDefaultValue.Present) {
                put(field.name, defaultValue.value)
            }
        }
        putAll(supplied)
        if (!keys.containsAll(expectedType.requiredFieldNames())) throw ClassCastException()
    }
}

internal fun ViaductSchema.Input.requiredFieldNames(): Set<String> =
    fields
        .filterTo(linkedSetOf()) { field ->
            !field.type.isNullable && !field.hasDefault
        }.mapTo(linkedSetOf(), ViaductSchema.Field::name)

internal fun ViaductSchema.Field.requiredArgsArePresentIn(fields: Map<String, *>): Boolean =
    args.all { arg ->
        arg.type.isNullable ||
            arg.hasDefault ||
            fields.containsKey(arg.name)
    }

internal fun ViaductSchema.HasDefaultValue.coercedDefaultValue(): CoercedDefaultValue =
    if (hasDefault) {
        CoercedDefaultValue.of(defaultValue.toEngineInputData(inputType))
    } else {
        CoercedDefaultValue.Absent
    }

private fun ViaductSchema.Literal.toEngineInputData(expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>): EngineInputData? {
    if (this is ViaductSchema.NullLiteral) {
        require(expectedType.isNullable)
        return null
    }

    val elementType = expectedType.unwrapList()
    if (elementType != null) {
        val elements =
            if (this is ViaductSchema.ListLiteral) {
                this
            } else {
                listOf(this)
            }
        return elements.map { it.toEngineInputData(elementType) }
    }

    return when (val type = expectedType.baseTypeDef) {
        is ViaductSchema.Scalar ->
            requireNotNull(
                scalarCoercers.getValue(type.name).coercing.parseLiteral(
                    toGraphQLJavaValue(),
                    CoercedVariables.emptyVariables(),
                    GraphQLContext.getDefault(),
                    Locale.getDefault(),
                ),
            ).toScalarInput(type.name)
        is ViaductSchema.Enum ->
            (this as ViaductSchema.EnumLit).value.also(type::requireValue)
        is ViaductSchema.Input -> {
            val supplied = this as ViaductSchema.ObjectLiteral
            buildMap {
                type.fields.forEach { field ->
                    val defaultValue = field.coercedDefaultValue()
                    if (defaultValue is CoercedDefaultValue.Present) {
                        put(field.name, defaultValue.value)
                    }
                }
                supplied.forEach { (name, literal) ->
                    val field = type.requireField(name)
                    put(name, literal.toEngineInputData(field.inputType))
                }
                require(keys.containsAll(type.requiredFieldNames()))
            }
        }
        else -> error("Unsupported input type: ${type.name}")
    }
}

private val scalarCoercers =
    (ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS + Scalars.viaductStandardScalars).associateBy { it.name }

private fun Map<*, *>.toStringKeyedMap(): EngineInputObjectData =
    entries.associate { (key, value) ->
        if (key !is String) throw ClassCastException()
        key to value
    }
