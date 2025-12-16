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
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RawSelectionSet
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
@InternalApi
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
     *
     * @param type the GraphQL type for which a [Conv] will be created
     * @param selectionSet a possible projection of [type].
     * The returned [Conv] will only be able to map the selections in [selectionSet].
     * Any aliases used in [selectionSet] will be used as object keys in both the EngineValue and IR Values
     */
    operator fun invoke(
        schema: ViaductSchema,
        type: GraphQLType,
        selectionSet: RawSelectionSet?
    ): Conv<Any?, IR.Value> = Builder(schema).build(type, selectionSet)

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
            forward = { eod ->
                val data = eod.getSelections().associateWith { sel -> eod.get(sel) }
                engineDataConv(data)
            },
            inverse = { ir ->
                val data = engineDataConv.invert(ir)
                ResolvedEngineObjectData(gqlType, data)
            },
            "engineObjectData-${gqlType.name}"
        )

    /**
     * @param convs a map of convs, keyed by their IR name
     * @param keyMap a map for transforming object keys:
     *   - when converting in the forward direction, IR keys will be computed by translating
     *     the keys of the input object through the forward key map
     *   - when converting in the inverse direction, output keys will be computed by translating
     *     the keys of the input IR object through the inverse key map
     */
    internal fun obj(
        name: String,
        convs: Map<String, Conv<Any?, IR.Value>>,
        keyMap: KeyMapping.Map = KeyMapping.Map.Identity,
    ): Conv<Map<String, Any?>, IR.Value.Object> =
        Conv(
            forward = { data ->
                // objects may have a KeyMapping applied, which can translate between field names and selection names.
                // We consider the engine value to have unmapped keys, and we would like to convert to an IR representation
                // with mapped keys
                // Thus, in the forward direction, every engine value key needs to be translated through the key mapper,
                // and we lookup the conv based on the mapped key.
                val selectionValues = data.flatMap { (k, v) ->
                    keyMap.forward(k).mapNotNull { mappedKey ->
                        val conv = convs[mappedKey] ?: return@mapNotNull null
                        mappedKey to conv(v)
                    }
                }.toMap()
                IR.Value.Object(name, selectionValues)
            },
            inverse = {
                // when inverting an IR object value, the keys in the IR object are assumed to have had a
                // KeyMapping applied.
                it.fields.toList().flatMap { (k, v) ->
                    val conv = requireNotNull(convs[k]) {
                        "Missing conv for key $name.$k"
                    }
                    val value = conv.invert(v)
                    keyMap.invert(k).map { engineKey ->
                        engineKey to value
                    }
                }.toMap()
            },
            "obj-$name"
        )

    internal fun enum(typeName: String): Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.String(it as String) },
            inverse = { (it as IR.Value.String).value },
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

        fun build(
            type: GraphQLType,
            selectionSet: RawSelectionSet?
        ): Conv<Any?, IR.Value> = mk(type, selectionSet).also { memo.finalize() }

        private fun mk(
            type: GraphQLType,
            selectionSet: RawSelectionSet?,
            isNullable: Boolean = true
        ): Conv<Any?, IR.Value> {
            val conv = when (type) {
                is GraphQLNonNull ->
                    return nonNull(mk(type.wrappedType, selectionSet, isNullable = false))

                is GraphQLList ->
                    list(mk(type.wrappedType, selectionSet))

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
                    memo.memoizeIf(type.name, selectionSet == null) {
                        val selectionConvs = mkSelectionConvs(schema, type, selectionSet, ::mk)
                        engineObjectData(
                            type,
                            obj(type.name, selectionConvs)
                        )
                    }.asAnyConv

                // this handles interfaces and unions
                is GraphQLCompositeType ->
                    memo.memoizeIf(type.name, selectionSet == null) {
                        val concretes = schema.rels.possibleObjectTypes(type).associate { type ->
                            val typedSelections = selectionSet?.selectionSetForType(type.name)

                            @Suppress("UNCHECKED_CAST")
                            val concrete = mk(type, typedSelections) as Conv<EngineObjectData.Sync, IR.Value.Object>
                            type.name to concrete
                        }
                        abstract(type.name, concretes)
                    }.asAnyConv

                is GraphQLInputObjectType ->
                    memo.buildIfAbsent(type.name) {
                        val fieldConvs = type.fields.associate { f -> f.name to mk(f.type, null) }
                        obj(type.name, fieldConvs).asAnyConv
                    }

                is GraphQLEnumType -> enum(type.name)

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
