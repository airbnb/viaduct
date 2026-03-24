package viaduct.mapping.graphql

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import java.time.temporal.TemporalAccessor

/**
 * [IR] is a special [Domain] that models an intermediate representation ("IR") for values.
 *
 * For any [Domain] to participate in object mapping, it must be able to define a mapping into
 * and out of this domain.
 *
 * @see [Domain.objectToIR]
 */
object IR : Domain<IR.Value.Object> {
    override val conv: Conv<Value.Object, Value.Object> = Conv.identity()

    sealed interface Value {
        /** A representation of a GraphQL boolean value */
        @JvmInline value class Boolean(val value: kotlin.Boolean) : Value

        /**
         * A representation of a GraphQL numeric value.
         * This representation is suitable for any numeric value, including those that are typically
         * serialized as Strings, such as Long and BigDecimal values.
         *
         * Number values are guaranteed to be roundtrippable into-then-outof this IR representation
         * without loss of precision, though mapping through IR between 2 different domains may
         * cause precision to be lost.
         */
        @JvmInline value class Number(val value: kotlin.Number) : Value {
            /** render this [Number] as an 8-bit Byte, between -128 and 127 */
            val byte: Byte get() = value.toByte()

            /** render this [Number] as a 32-bit Int */
            val int: Int get() = value.toInt()

            /** render this [Number] as a 16-bit Short */
            val short: Short get() = value.toShort()

            /** render this [Number] as a 64-bit Long */
            val long: Long get() = value.toLong()

            /** render this [Number] as an arbitrary-precision BigInteger */
            val bigInteger: BigInteger
                get() = value as? BigInteger ?: value.toLong().toBigInteger()

            /** render this [Number] as a single-precision floating point */
            val float: Float get() = value as? Float ?: value.toFloat()

            /** render this [Number] as a double-precision floating point */
            val double: Double get() = value as? Double ?: value.toDouble()

            /** render this [Number] as an arbitrary-precision floating point */
            val bigDecimal: BigDecimal
                get() = value as? BigDecimal ?: value.toDouble().toBigDecimal()
        }

        /**
         * A representation of a GraphQL string value.
         * This representation is suitable for values of String, ID, and JSON
         * scalar types.
         */
        @JvmInline value class String(val value: kotlin.String) : Value

        /** A representation of a GraphQL list value */
        @JvmInline value class List(val value: kotlin.collections.List<Value>) : Value {
            constructor(vararg values: Value) : this(values.toList())
        }

        /**
         * A representation of a GraphQL temporal value.
         * This representation is suitable for values of the DateTime, Date, and Time types
         */
        @JvmInline value class Time(val value: TemporalAccessor) : Value {
            /** render this [Time] to an [Instant] */
            val instant: Instant get() = Instant.from(value)

            /** render this [Time] to a [LocalDate] */
            val localDate: LocalDate get() = LocalDate.from(value)

            /** render this [Time] to an [OffsetTime] */
            val offsetTime: OffsetTime get() = OffsetTime.from(value)
        }

        /**
         * A representation of a GraphQL null value
         * This representation is suitable for explicit null values but should not be used to model
         * a missing value, such as an unset input field, or the value of an unselected output field
         */
        object Null : Value {
            override fun toString(): kotlin.String = "NULL"
        }

        /**
         * A representation of a GraphQL input or output object value
         *
         * @param name the name of a GraphQL input or output object type.
         * @param fields an ordered mapping of fields and their values. Any key in this map must
         *   correspond to a field of the same name on the type with name [name].
         *   Any field that is defined on the corresponding type that is not present in [fields]
         *   will be interpreted as unset in the case of an input field, or unselected in
         *   the case of an output field.
         */
        data class Object(val name: kotlin.String, val fields: Map<kotlin.String, Value>) : Value {
            constructor(name: kotlin.String, vararg fields: Pair<kotlin.String, Value>) : this(name, fields.toMap())
        }
    }
}
