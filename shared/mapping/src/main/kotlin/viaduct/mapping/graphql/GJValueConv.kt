package viaduct.mapping.graphql

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import viaduct.mapping.graphql.GJValueConv.invoke

/**
 * [Conv] factory for converting graphql-java [Value] representations to [IR.Value]
 * @see invoke
 */
object GJValueConv {
    /** return a [Conv] for the provided [type] that can translate between [Value] and [IR.Value] values */
    operator fun invoke(type: GraphQLType): Conv<Value<*>, IR.Value> = Builder().build(type)

    private val nullValue = NullValue.of()

    private fun nonNull(inner: Conv<Value<*>, IR.Value>): Conv<Value<*>, IR.Value> =
        Conv(
            forward = {
                require(it !is NullValue)
                inner(it)
            },
            inverse = {
                require(it !is IR.Value.Null)
                inner.invert(it)
            },
            "nonNull-$inner"
        )

    private val byte: Conv<Value<*>, IR.Value> = Conv(
        forward = { IR.Value.Number((it as IntValue).value.byteValueExact()) },
        inverse = { IntValue((it as IR.Value.Number).bigInteger) },
        "byte"
    )

    private val short: Conv<Value<*>, IR.Value> = Conv(
        forward = { IR.Value.Number((it as IntValue).value.shortValueExact()) },
        inverse = { IntValue((it as IR.Value.Number).bigInteger) },
        "short"
    )

    private val int: Conv<Value<*>, IR.Value> = Conv(
        forward = { IR.Value.Number((it as IntValue).value.intValueExact()) },
        inverse = { IntValue((it as IR.Value.Number).bigInteger) },
        "int"
    )

    private val long: Conv<Value<*>, IR.Value> = Conv(
        forward = { IR.Value.Number((it as IntValue).value.longValueExact()) },
        inverse = { IntValue((it as IR.Value.Number).bigInteger) },
        "long"
    )

    private val float: Conv<Value<*>, IR.Value> = Conv(
        forward = { IR.Value.Number((it as FloatValue).value.toDouble()) },
        inverse = { FloatValue((it as IR.Value.Number).bigDecimal) },
        "float"
    )

    private val boolean: Conv<Value<*>, IR.Value> = Conv(
        forward = { IR.Value.Boolean((it as BooleanValue).isValue) },
        inverse = { BooleanValue((it as IR.Value.Boolean).value) },
        "boolean"
    )

    private fun enum(name: String): Conv<Value<*>, IR.Value> =
        Conv(
            forward = { IR.Value.String((it as EnumValue).name) },
            inverse = { EnumValue((it as IR.Value.String).value) },
            "enum:$name"
        )

    private fun nullable(inner: Conv<Value<*>, IR.Value>): Conv<Value<*>, IR.Value> =
        Conv(
            forward = {
                if (it is NullValue) {
                    IR.Value.Null
                } else {
                    inner(it)
                }
            },
            inverse = {
                if (it is IR.Value.Null) {
                    nullValue
                } else {
                    inner.invert(it)
                }
            },
            "nullable-$inner"
        )

    private val string: Conv<Value<*>, IR.Value> = Conv(
        forward = { IR.Value.String((it as StringValue).value) },
        inverse = { StringValue((it as IR.Value.String).value) },
        "string"
    )

    private val date: Conv<Value<*>, IR.Value> = Conv(
        forward = {
            it as StringValue
            IR.Value.Time(LocalDate.parse(it.value))
        },
        inverse = {
            it as IR.Value.Time
            StringValue(it.localDate.toString())
        },
        "date"
    )

    private val dateTime: Conv<Value<*>, IR.Value> = Conv(
        forward = {
            it as StringValue
            IR.Value.Time(Instant.parse(it.value))
        },
        inverse = {
            it as IR.Value.Time
            StringValue(it.instant.toString())
        },
        "dateTime"
    )

    private val time: Conv<Value<*>, IR.Value> = Conv(
        forward = {
            it as StringValue
            IR.Value.Time(OffsetTime.parse(it.value))
        },
        inverse = {
            it as IR.Value.Time
            StringValue(it.offsetTime.toString())
        },
        "time"
    )

    private fun array(item: Conv<Value<*>, IR.Value>): Conv<Value<*>, IR.Value> =
        Conv(
            forward = {
                IR.Value.List((it as ArrayValue).values.map(item))
            },
            inverse = {
                ArrayValue((it as IR.Value.List).value.map(item::invert))
            },
            "array-$item"
        )

    private fun objectValue(
        name: String,
        fields: Map<String, Conv<Value<*>, IR.Value>>
    ): Conv<Value<*>, IR.Value> =
        Conv(
            forward = {
                it as ObjectValue
                val irFields = it.objectFields.fold(emptyMap<String, IR.Value>()) { acc, gjField ->
                    val conv = fields[gjField.name] ?: return@fold acc
                    acc + (gjField.name to conv(gjField.value))
                }
                IR.Value.Object(name, irFields)
            },
            inverse = {
                it as IR.Value.Object
                val gjFields = it.fields.map { (fname, ir) ->
                    val conv = requireNotNull(fields[fname])
                    ObjectField(fname, conv.invert(ir))
                }
                ObjectValue(gjFields)
            },
            "object:$name"
        )

    private class Builder {
        private val memo = ConvMemo()

        fun build(type: GraphQLType): Conv<Value<*>, IR.Value> = mk(type).also { memo.finalize() }

        private fun mk(
            type: GraphQLType,
            isNullable: Boolean = true
        ): Conv<Value<*>, IR.Value> {
            val conv = when (type) {
                is GraphQLNonNull -> return nonNull(mk(type.wrappedType, false))
                is GraphQLList -> array(mk(type.wrappedType))
                is GraphQLScalarType -> when (type.name) {
                    "Boolean" -> boolean
                    "Byte" -> byte
                    "Date" -> date
                    "DateTime" -> dateTime
                    "Float" -> float
                    "ID" -> string
                    "Int" -> int
                    "Long" -> long
                    "JSON" -> string
                    "Short" -> short
                    "String" -> string
                    "Time" -> time
                    else -> throwUnsupported(type)
                }

                is GraphQLEnumType ->
                    memo.buildIfAbsent(type.name) {
                        enum(type.name)
                    }

                is GraphQLInputObjectType -> {
                    memo.buildIfAbsent(type.name) {
                        val fieldConvs = type.fields.associate { f -> f.name to mk(f.type) }
                        objectValue(type.name, fieldConvs)
                    }
                }

                else -> throwUnsupported(type)
            }
            return if (isNullable) {
                nullable(conv)
            } else {
                conv
            }
        }
    }

    private fun throwUnsupported(type: GraphQLType): Nothing = throw IllegalArgumentException("Cannot create a Conv for unsupported GraphQLType $type")
}
