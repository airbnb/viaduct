package viaduct.api.internal

import com.fasterxml.jackson.databind.ObjectMapper
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import java.time.temporal.TemporalAccessor
import viaduct.api.internal.EngineValueConv.invoke
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.ViaductSchema
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.ConvMemo
import viaduct.mapping.graphql.IR

/**
 * Factory methods for [Conv]s that map between Viaduct engine and [IR] representations.
 *
 * @see invoke
 */
object EngineValueConv {
    private val objectMapper = ObjectMapper()

    /**
     * Create a [Conv] for the provided [type] that maps values between their Viaduct engine and
     * [IR] representations.
     *
     * Example:
     * ```
     *   val conv = EngineValueConv(schema, Scalars.GraphQLInt)
     *
     *   // engine -> IR
     *   conv(1)                      // IR.Value.Number(1)
     *
     *   // IR -> engine
     *   conv.invert(IR.Value.Null)   // null
     * ```
     */
    operator fun invoke(
        schema: ViaductSchema,
        type: GraphQLType
    ): Conv<Any?, IR.Value> = Builder(schema).build(type)

    @Suppress("ObjectPropertyNaming")
    private val __typename: String = "__typename"

    internal fun nullable(inner: Conv<Any?, IR.Value>): Conv<Any?, IR.Value> =
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

    internal fun nonNull(inner: Conv<Any?, IR.Value>): Conv<Any?, IR.Value> =
        Conv(
            forward = {
                requireNotNull(it)
                inner(it)
            },
            inverse = {
                require(it != IR.Value.Null)
                inner.invert(it)
            },
            "nonNull-$inner"
        )

    internal fun list(inner: Conv<Any?, IR.Value>): Conv<Any?, IR.Value> =
        Conv(
            forward = {
                it as List<*>
                IR.Value.List(it.map(inner))
            },
            inverse = {
                (it as IR.Value.List).value.map(inner::invert)
            },
            "list-$inner"
        )

    internal val byte: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number(it as Number) },
            inverse = { (it as IR.Value.Number).byte },
            "byte"
        )

    internal val short: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number(it as Number) },
            inverse = { (it as IR.Value.Number).short },
            "short"
        )

    internal val int: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number(it as Number) },
            inverse = { (it as IR.Value.Number).int },
            "int"
        )

    internal val long: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number(it as Number) },
            inverse = { (it as IR.Value.Number).long },
            "long"
        )

    internal val float: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number(it as Number) },
            inverse = { (it as IR.Value.Number).double },
            "float"
        )

    internal val boolean: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Boolean(it as Boolean) },
            inverse = { (it as IR.Value.Boolean).value },
            "boolean"
        )

    internal val date: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Time(it as TemporalAccessor) },
            inverse = { (it as IR.Value.Time).localDate },
            "date"
        )

    internal val dateTime: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Time(it as TemporalAccessor) },
            inverse = { (it as IR.Value.Time).instant },
            "dateTime"
        )

    internal val time: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Time(it as TemporalAccessor) },
            inverse = { (it as IR.Value.Time).offsetTime },
            "time"
        )

    internal val string: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.String(it as String) },
            inverse = { (it as IR.Value.String).value },
            "string"
        )

    // JSON scalar values are represented as IR Strings but are in their deserialized form
    // when in the engine
    internal val json: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.String(objectMapper.writeValueAsString(it)) },
            inverse = { objectMapper.readValue((it as IR.Value.String).value, Any::class.java) },
            "json"
        )

    // TODO: support BackingData in object mapping
    //  https://app.asana.com/1/150975571430/project/1211295233988904/task/1211525978501301
    internal val backingData: Conv<Any?, IR.Value> =
        Conv(forward = { IR.Value.Null }, inverse = { null }, "backingData")

    internal fun engineObjectData(
        gqlType: GraphQLObjectType,
        engineDataConv: Conv<Map<String, Any?>, IR.Value.Object>
    ): Conv<EngineObjectData.Sync, IR.Value.Object> =
        Conv(
            forward = {
                val data = it.getSelections().associate { sel -> sel to it.get(sel) }
                engineDataConv(data)
            },
            inverse = {
                val data = engineDataConv.invert(it)
                ResolvedEngineObjectData(gqlType, data)
            },
            "engineObjectData-${gqlType.name}"
        )

    internal fun obj(
        name: String,
        fieldConvs: Map<String, Conv<Any?, IR.Value>>
    ): Conv<Map<String, Any?>, IR.Value.Object> {
        val allConvs = fieldConvs + (__typename to string)

        return Conv(
            forward = {
                val fieldValues = it.mapNotNull { (k, v) ->
                    val conv = allConvs[k] ?: return@mapNotNull null
                    k to conv(v)
                }.toMap()
                IR.Value.Object(name, fieldValues)
            },
            inverse = {
                it.fields.mapValues { (k, v) ->
                    val conv = requireNotNull(allConvs[k]) {
                        "Missing conv for field $name.$k"
                    }
                    conv.invert(v)
                }
            },
            "obj-$name"
        )
    }

    internal fun enum(
        typeName: String,
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
            "enum-$typeName"
        )

    internal fun abstract(
        typeName: String,
        concreteConvs: Map<String, Conv<EngineObjectData.Sync, IR.Value.Object>>
    ): Conv<EngineObjectData.Sync, IR.Value.Object> =
        Conv(
            forward = {
                val concrete = it.graphQLObjectType.name
                val concreteConv = requireNotNull(concreteConvs[concrete])
                concreteConv(it)
            },
            inverse = {
                val concreteConv = requireNotNull(concreteConvs[it.name])
                concreteConv.invert(it)
            },
            "abstract-$typeName"
        )

    private class Builder(val schema: ViaductSchema) {
        private val memo = ConvMemo()

        fun build(type: GraphQLType): Conv<Any?, IR.Value> = mk(type).also { memo.finalize() }

        private fun mk(
            type: GraphQLType,
            isNullable: Boolean = true
        ): Conv<Any?, IR.Value> {
            val conv = when (type) {
                is GraphQLNonNull ->
                    return nonNull(mk(type.wrappedType, isNullable = false))

                is GraphQLList ->
                    list(mk(type.wrappedType))

                is GraphQLScalarType -> when (type.name) {
                    "BackingData" -> backingData
                    "Boolean" -> boolean
                    "Byte" -> byte
                    "Date" -> date
                    "DateTime" -> dateTime
                    "Float" -> float
                    "ID" -> string
                    "Int" -> int
                    "Long" -> long
                    "JSON" -> json
                    "Short" -> short
                    "String" -> string
                    "Time" -> time
                    else -> throwUnsupported(type)
                }

                is GraphQLObjectType ->
                    memo.buildIfAbsent(type.name) {
                        val fieldConvs = type.fields.associate { f -> f.name to mk(f.type) }
                        engineObjectData(
                            type,
                            obj(type.name, fieldConvs)
                        )
                    }.asAnyConv

                // this handles interfaces and unions
                is GraphQLCompositeType ->
                    memo.buildIfAbsent(type.name) {
                        val members = schema.rels.possibleObjectTypes(type).toList()
                        val memberConvs = members.associate {
                            @Suppress("UNCHECKED_CAST")
                            val memberConv = mk(it) as Conv<EngineObjectData.Sync, IR.Value.Object>
                            it.name to memberConv
                        }
                        abstract(type.name, memberConvs)
                    }.asAnyConv

                is GraphQLInputObjectType ->
                    memo.buildIfAbsent(type.name) {
                        val fieldConvs = type.fields.associate { f -> f.name to mk(f.type) }
                        obj(type.name, fieldConvs).asAnyConv
                    }

                is GraphQLEnumType ->
                    enum(type.name, type.values.map { it.name }.toSet())

                else -> throwUnsupported(type)
            }

            return if (isNullable) {
                nullable(conv)
            } else {
                conv
            }
        }

        private fun throwUnsupported(type: GraphQLType): Nothing = throw IllegalArgumentException("Cannot create a Conv for unsupported GraphQLType $type")
    }
}
