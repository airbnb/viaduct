package viaduct.mapping.graphql

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLUnionType
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import viaduct.engine.api.ViaductSchema

/**
 * Factory methods for [Conv]s that map between Json and [IR] representations.
 *
 * @see invoke
 */
object JsonConv {
    private val objectMapper = ObjectMapper()
    @Suppress("ObjectPropertyNaming")
    private val __typename = "__typename"

    /** create a [Conv] for the given [type] */
    operator fun invoke(
        schema: ViaductSchema,
        type: GraphQLType
    ): Conv<String, IR.Value> = Builder(schema).build(type)

    private fun json(inner: Conv<Any?, IR.Value>): Conv<String, IR.Value> =
        Conv(
            forward = { inner(objectMapper.readValue(it, Any::class.java)) },
            inverse = { objectMapper.writeValueAsString(inner.invert(it)) },
            "json-$inner"
        )

    private fun list(inner: Conv<Any?, IR.Value>): Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.List((it as List<*>).map(inner)) },
            inverse = { (it as IR.Value.List).value.map(inner::invert) },
            "list-$inner"
        )

    private fun obj(
        name: String,
        fieldConvs: Map<String, Conv<Any?, IR.Value>>
    ): Conv<Any?, IR.Value> =
        Conv(
            forward = {
                @Suppress("UNCHECKED_CAST")
                it as Map<String, Any?>
                val irFieldValues = it.toList().mapNotNull { (k, v) ->
                    val conv = fieldConvs[k] ?: return@mapNotNull null
                    k to conv(v)
                }.toMap()
                IR.Value.Object(name, irFieldValues)
            },
            inverse = {
                it as IR.Value.Object
                it.fields.mapValues { (k, v) ->
                    val fieldConv = requireNotNull(fieldConvs[k])
                    fieldConv.invert(v)
                }
            },
            "obj-$name"
        )

    private fun abstract(
        name: String,
        concretes: Map<String, Conv<Any?, IR.Value>>
    ): Conv<Any?, IR.Value> =
        Conv(
            forward = {
                @Suppress("UNCHECKED_CAST")
                it as Map<String, Any?>
                val typeName = requireNotNull(it[__typename]) {
                    "Cannot resolve concrete type for abstract type $name"
                }
                val concrete = requireNotNull(concretes[typeName])
                concrete(it)
            },
            inverse = {
                it as IR.Value.Object
                val concrete = requireNotNull(concretes[it.name])
                @Suppress("UNCHECKED_CAST")
                val result = concrete.invert(it) as Map<String, Any?>
                result + (__typename to it.name)
            },
            "abstract-$name"
        )

    private val boolean: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Boolean(it as Boolean) },
            inverse = { (it as IR.Value.Boolean).value },
            "boolean"
        )

    private val int: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toInt()) },
            inverse = { (it as IR.Value.Number).int },
            "int"
        )

    private val byte: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toByte()) },
            inverse = { (it as IR.Value.Number).byte },
            "byte"
        )

    private val short: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toShort()) },
            inverse = { (it as IR.Value.Number).short },
            "short"
        )

    private val long: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toLong()) },
            inverse = { (it as IR.Value.Number).long },
            "long"
        )

    private val float: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toDouble()) },
            inverse = { (it as IR.Value.Number).double },
            "float"
        )

    private val string: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.String(it as String) },
            inverse = { (it as IR.Value.String).value },
            "string"
        )

    private val date: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Time(LocalDate.parse(it as String)) },
            inverse = { (it as IR.Value.Time).localDate.toString() },
            "date"
        )

    private val instant: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Time(Instant.parse(it as String)) },
            inverse = { (it as IR.Value.Time).instant.toString() },
            "instant"
        )

    private val time: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Time(OffsetTime.parse(it as String)) },
            inverse = { (it as IR.Value.Time).offsetTime.toString() },
            "time"
        )

    private fun enum(
        name: String,
        values: Set<String>
    ): Conv<Any?, IR.Value> =
        Conv(
            forward = {
                it as String
                require(it in values)
                IR.Value.String(it)
            },
            inverse = {
                it as IR.Value.String
                require(it.value in values)
                it.value
            },
            "enum-$name"
        )

    private fun nonNullable(inner: Conv<Any?, IR.Value>): Conv<Any?, IR.Value> =
        Conv(
            forward = { inner(requireNotNull(it)) },
            inverse = {
                require(it != IR.Value.Null)
                inner.invert(it)
            },
            "nonNullable-$inner"
        )

    private fun nullable(inner: Conv<Any?, IR.Value>): Conv<Any?, IR.Value> =
        Conv(
            forward = {
                if (it == null) {
                    IR.Value.Null
                } else {
                    inner(it)
                }
            },
            inverse = {
                if (it == IR.Value.Null) {
                    null
                } else {
                    inner.invert(it)
                }
            },
            "nullable-$inner"
        )

    /** A builder that can recursively build a single conv */
    private class Builder(val schema: ViaductSchema) {
        private val convMemo = ConvMemo()

        fun build(type: GraphQLType): Conv<String, IR.Value> =
            json(mk(type)).also {
                convMemo.finalize()
            }

        private fun mk(
            type: GraphQLType,
            isNullable: Boolean = true
        ): Conv<Any?, IR.Value> {
            val conv = when (type) {
                is GraphQLNonNull -> {
                    // return early to bypass the additional wrapping in nullable
                    return nonNullable(mk(type.wrappedType, isNullable = false))
                }
                is GraphQLList -> list(mk(type.wrappedType))
                is GraphQLObjectType ->
                    convMemo.buildIfAbsent(type.name) {
                        val fieldConvs = type.fields.associate { f -> f.name to mk(f.type) } +
                            (__typename to string)
                        obj(type.name, fieldConvs)
                    }

                is GraphQLUnionType ->
                    convMemo.buildIfAbsent(type.name) {
                        val concretes = type.types.associate { type -> type.name to mk(type) }
                        abstract(type.name, concretes)
                    }

                is GraphQLInterfaceType ->
                    convMemo.buildIfAbsent(type.name) {
                        val concretes = schema.schema.getImplementations(type)
                            .associate { type ->
                                type.name to mk(type)
                            }
                        abstract(type.name, concretes)
                    }

                is GraphQLInputObjectType ->
                    convMemo.buildIfAbsent(type.name) {
                        val fieldConvs = type.fields.associate { f -> f.name to mk(f.type) }
                        obj(type.name, fieldConvs)
                    }

                is GraphQLEnumType -> enum(type.name, type.values.map { it.name }.toSet())

                is GraphQLScalarType -> when (type.name) {
                    "Boolean" -> boolean
                    "Date" -> date
                    "DateTime" -> instant
                    "Time" -> time
                    "Int" -> int
                    "Short" -> short
                    "Long" -> long
                    "Byte" -> byte
                    "Float" -> float
                    "ID", "String", "JSON" -> string
                    else ->
                        throw IllegalArgumentException("Cannot generate JsonConv for type $type")
                }

                else ->
                    throw IllegalArgumentException("Cannot generate JsonConv for type $type")
            }

            return if (isNullable) {
                nullable(conv)
            } else {
                conv
            }
        }
    }
}
