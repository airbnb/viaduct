package viaduct.mapping.graphql

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import viaduct.engine.api.ViaductSchema

/**
 * Factory methods for [Conv]s that map between Json and [IR] representations.
 *
 * @see JsonConv.invoke
 */
object JsonConv {
    private val objectMapper = ObjectMapper()
    @Suppress("ObjectPropertyNaming")
    private val __typename = "__typename"

    enum class AddJsonTypenameField {
        /**
         * For all input and output graphql objects, a __typename field
         * will always be added to the emitted json, even when __typename is not
         * in the original [IR.Value.Object] field values.
         */
        Always,

        /**
         * A __typename field will never be added to the emitted json for
         * input and output graphql objects. Though if __typename was in
         * the original [IR.Value.Object] field values then it will be present
         * in the converted json.
         */
        Never
    }

    /** create a [Conv] for the given [type] */
    operator fun invoke(
        schema: ViaductSchema,
        type: GraphQLType,
        addJsonTypenameField: AddJsonTypenameField = AddJsonTypenameField.Always
    ): Conv<String, IR.Value> = Builder(schema, addJsonTypenameField).build(type)

    private fun json(inner: Conv<Any?, IR.Value>): Conv<String, IR.Value> =
        Conv(
            forward = { inner(objectMapper.readValue(it, Any::class.java)) },
            inverse = { objectMapper.writeValueAsString(inner.invert(it)) },
            "json-$inner"
        )

    private fun list(inner: Conv<Any?, IR.Value>): Conv<List<Any?>, IR.Value.List> =
        Conv(
            forward = { IR.Value.List(it.map(inner)) },
            inverse = { it.value.map(inner::invert) },
            "list-$inner"
        )

    private fun obj(
        name: String,
        fieldConvs: Map<String, Conv<Any?, IR.Value>>,
        addJsonTypenameField: AddJsonTypenameField
    ): Conv<Map<String, Any?>, IR.Value.Object> =
        Conv(
            forward = {
                val irFieldValues = it.toList().mapNotNull { (k, v) ->
                    val conv = fieldConvs[k] ?: return@mapNotNull null
                    k to conv(v)
                }.toMap()
                IR.Value.Object(name, irFieldValues)
            },
            inverse = {
                val result = it.fields.mapValues { (k, v) ->
                    val fieldConv = requireNotNull(fieldConvs[k])
                    fieldConv.invert(v)
                }
                when (addJsonTypenameField) {
                    AddJsonTypenameField.Always -> result + (__typename to name)
                    AddJsonTypenameField.Never -> result
                }
            },
            "obj-$name"
        )

    private fun abstract(
        name: String,
        concretes: Map<String, Conv<Map<String, Any?>, IR.Value.Object>>
    ): Conv<Map<String, Any?>, IR.Value.Object> =
        Conv(
            forward = {
                val typeName = requireNotNull(it[__typename]) {
                    "Cannot resolve concrete type for abstract type $name"
                }
                val concrete = requireNotNull(concretes[typeName])
                concrete(it)
            },
            inverse = {
                val concrete = requireNotNull(concretes[it.name])
                val result = concrete.invert(it)
                result + (__typename to it.name)
            },
            "abstract-$name"
        )

    private val boolean: Conv<Boolean, IR.Value.Boolean> =
        Conv(
            forward = { IR.Value.Boolean(it) },
            inverse = { it.value },
            "boolean"
        )

    private val int: Conv<Number, IR.Value.Number> =
        Conv(
            forward = { IR.Value.Number((it).toInt()) },
            inverse = { it.int },
            "int"
        )

    private val byte: Conv<Number, IR.Value.Number> =
        Conv(
            forward = { IR.Value.Number((it).toByte()) },
            inverse = { it.byte },
            "byte"
        )

    private val short: Conv<Number, IR.Value.Number> =
        Conv(
            forward = { IR.Value.Number((it).toShort()) },
            inverse = { it.short },
            "short"
        )

    private val long: Conv<Number, IR.Value.Number> =
        Conv(
            forward = { IR.Value.Number((it).toLong()) },
            inverse = { it.long },
            "long"
        )

    private val float: Conv<Number, IR.Value.Number> =
        Conv(
            forward = { IR.Value.Number(it.toDouble()) },
            inverse = { it.double },
            "float"
        )

    private val string: Conv<String, IR.Value.String> =
        Conv(
            forward = { IR.Value.String(it) },
            inverse = { it.value },
            "string"
        )

    private val date: Conv<String, IR.Value.Time> =
        Conv(
            forward = { IR.Value.Time(LocalDate.parse(it)) },
            inverse = { it.localDate.toString() },
            "date"
        )

    private val instant: Conv<String, IR.Value.Time> =
        Conv(
            forward = { IR.Value.Time(Instant.parse(it)) },
            inverse = { it.instant.toString() },
            "instant"
        )

    private val time: Conv<String, IR.Value.Time> =
        Conv(
            forward = { IR.Value.Time(OffsetTime.parse(it)) },
            inverse = { it.offsetTime.toString() },
            "time"
        )

    private val backingData: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Null },
            inverse = { null },
            "backingData"
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
    private class Builder(val schema: ViaductSchema, val addJsonTypenameField: AddJsonTypenameField) {
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
                        val fieldConvs: Map<String, Conv<Any?, IR.Value>> = type.fields
                            .associate { f ->
                                f.name to mk(f.type)
                            } + (__typename to string.asAnyConv)
                        obj(type.name, fieldConvs, addJsonTypenameField)
                    }

                is GraphQLCompositeType ->
                    convMemo.buildIfAbsent(type.name) {
                        val concretes = schema.rels.possibleObjectTypes(type).associate { type ->
                            @Suppress("UNCHECKED_CAST")
                            val concrete = mk(type) as Conv<Map<String, Any?>, IR.Value.Object>
                            type.name to concrete
                        }
                        abstract(type.name, concretes)
                    }

                is GraphQLInputObjectType ->
                    convMemo.buildIfAbsent(type.name) {
                        val fieldConvs = type.fields.associate { f -> f.name to mk(f.type) }
                        obj(type.name, fieldConvs, addJsonTypenameField)
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
                    "BackingData" -> backingData
                    else ->
                        throw IllegalArgumentException("Cannot generate JsonConv for type $type")
                }

                else ->
                    throw IllegalArgumentException("Cannot generate JsonConv for type $type")
            }

            return if (isNullable) {
                nullable(conv.asAnyConv)
            } else {
                conv.asAnyConv
            }
        }

        @Suppress("UNCHECKED_CAST")
        val Conv<*, *>.asAnyConv: Conv<Any?, IR.Value> get() = this as Conv<Any?, IR.Value>
    }
}
