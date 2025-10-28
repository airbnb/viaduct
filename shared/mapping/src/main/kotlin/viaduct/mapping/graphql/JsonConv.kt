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

object JsonConv {
    private val objectMapper = ObjectMapper()
    @Suppress("ObjectPropertyNaming")
    private val __typename = "__typename"

    /** create a [JsonConv] for the given [type] */
    operator fun invoke(
        schema: ViaductSchema,
        type: GraphQLType
    ): Conv<String, IR.Value> = Builder(schema).build(type)

    private fun json(inner: Conv<Any?, IR.Value>): Conv<String, IR.Value> =
        Conv(
            forward = { inner(objectMapper.readValue(it, Any::class.java)) },
            inverse = { objectMapper.writeValueAsString(inner.invert(it)) }
        )

    private fun list(inner: Conv<Any?, IR.Value>): Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.List((it as List<*>).map(inner)) },
            inverse = { (it as IR.Value.List).value.map(inner::invert) }
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
            }
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
            }
        )

    private val boolean: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Boolean(it as Boolean) },
            inverse = { (it as IR.Value.Boolean).value }
        )

    private val int: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toInt()) },
            inverse = { (it as IR.Value.Number).int }
        )

    private val byte: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toByte()) },
            inverse = { (it as IR.Value.Number).byte }
        )

    private val short: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toShort()) },
            inverse = { (it as IR.Value.Number).short }
        )

    private val long: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toLong()) },
            inverse = { (it as IR.Value.Number).long }
        )

    private val float: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Number((it as Number).toDouble()) },
            inverse = { (it as IR.Value.Number).double }
        )

    private val string: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.String(it as String) },
            inverse = { (it as IR.Value.String).value }
        )

    private val date: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Time(LocalDate.parse(it as String)) },
            inverse = { (it as IR.Value.Time).localDate.toString() }
        )

    private val instant: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Time(Instant.parse(it as String)) },
            inverse = { (it as IR.Value.Time).instant.toString() }
        )

    private val time: Conv<Any?, IR.Value> =
        Conv(
            forward = { IR.Value.Time(OffsetTime.parse(it as String)) },
            inverse = { (it as IR.Value.Time).offsetTime.toString() }
        )

    private fun enum(values: Set<String>): Conv<Any?, IR.Value> =
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
            }
        )

    private fun nonNullable(inner: Conv<Any?, IR.Value>): Conv<Any?, IR.Value> =
        Conv(
            forward = { inner(requireNotNull(it)) },
            inverse = {
                require(it != IR.Value.Null)
                inner.invert(it)
            }
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
            }
        )

    /** A builder that can recursively build a single conv */
    private class Builder(val schema: ViaductSchema) {
        private val cache = ConvCache()

        fun build(type: GraphQLType): Conv<String, IR.Value> =
            json(mk(type)).also {
                cache.finalize()
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
                    cache.computeIfAbsent(type.name) {
                        val fieldConvs = type.fields
                            .map { f -> f.name to mk(f.type) }
                            .toMap()
                        obj(type.name, fieldConvs)
                    }

                is GraphQLUnionType ->
                    cache.computeIfAbsent(type.name) {
                        val concretes = type.types.map { type ->
                            type.name to mk(type)
                        }.toMap()
                        abstract(type.name, concretes)
                    }

                is GraphQLInterfaceType ->
                    cache.computeIfAbsent(type.name) {
                        val concretes = schema.schema.getImplementations(type).map { type ->
                            type.name to mk(type)
                        }.toMap()
                        abstract(type.name, concretes)
                    }

                is GraphQLInputObjectType ->
                    cache.computeIfAbsent(type.name) {
                        val fieldConvs = type.fields
                            .map { f -> f.name to mk(f.type) }
                            .toMap()
                        obj(type.name, fieldConvs)
                    }

                is GraphQLEnumType -> enum(type.values.map { it.name }.toSet())

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

    /**
     * [Builder] eagerly builds a full Conv to map any value for a given type.
     * This eagerness creates issues around cyclic objects -- eagerly building a conv for every field will cause
     * an infinite loop.
     *
     * [ConvCache] helps with this by returning a [LateInitConv] when it detects a cycle,
     * allowing Conv construction to happen in a finite amount of time for cyclic type graphs.
     */
    private class ConvCache {
        private val building = mutableSetOf<String>()
        private val lateinits = mutableListOf<LateInitConv>()
        private val convs = mutableMapOf<String, Conv<Any?, IR.Value>>()
        private var finalized = false

        fun computeIfAbsent(
            typeName: String,
            fn: () -> Conv<Any?, IR.Value>
        ): Conv<Any?, IR.Value> {
            if (finalized) {
                throw IllegalStateException("ObjectConvCache cannot be used after being finalized")
            }

            return if (typeName in building) {
                // cycle detected!
                // return a LateInitConv which will be completed when finalize is called
                val conv = LateInitConv(typeName)
                lateinits += conv
                conv
            } else {
                // no cycle detected.
                // Add the current type to the set of convs being built
                building += typeName
                val conv = fn()
                building -= typeName
                convs[typeName] = conv
                conv
            }
        }

        /** Initialize all [LateInitConv]s */
        fun finalize() {
            lateinits.forEach { lateInitConv ->
                val conv = requireNotNull(convs[lateInitConv.name]) {
                    "Unsatisfied Conv ref: ${lateInitConv.name}"
                }
                lateInitConv.inner = conv
            }
            finalized = true
        }
    }

    /**
     * A Conv that can be lazily constructed, allowing for cyclic references.
     * @see [ConvCache]
     */
    private class LateInitConv(val name: String) : Conv<Any?, IR.Value> {
        lateinit var inner: Conv<Any?, IR.Value>

        override fun invoke(from: Any?): IR.Value = inner(from)

        override fun inverse(): Conv<IR.Value, Any?> = inner.inverse()
    }
}
