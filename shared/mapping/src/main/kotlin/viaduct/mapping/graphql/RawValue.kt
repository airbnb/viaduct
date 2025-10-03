package viaduct.mapping.graphql

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * A RawValue describes an intermediate representation for data.
 * Its utility is in being able to describe data in a way that can be mapped to
 * a [graphql.language.Value], a GRT value, or other value domains.
 *
 * RawValue types are similar to [graphql.language.Value] types, with some key differences:
 * - RawValue includes a [RawObject] type for modeling typed output object values
 * - RawValue includes [RawINull] for modeling implicit null values
 */
sealed interface RawValue {
    /**
     * A DSL to make it easier to construct [RawValue] instances.
     * Callers may either import the companion object or extend this class.
     *
     * Example:
     * ```kotlin
     *   class MyTest : RawValue.DSL() {
     *      obj("Foo", "foo" to list(1.scalar))
     *   }
     * ```
     */
    abstract class DSL {
        val enull: RawENull = RawENull
        val inull: RawINull = RawINull

        fun Any.scalar(typename: String): RawScalar = RawScalar(typename, this)

        val Boolean.scalar: RawScalar get() = RawScalar("Boolean", this)
        val Double.scalar: RawScalar get() = RawScalar("Float", this)
        val Int.scalar: RawScalar get() = RawScalar("Int", this)
        val Long.scalar: RawScalar get() = RawScalar("Long", this)
        val Short.scalar: RawScalar get() = RawScalar("Short", this)
        val String.scalar: RawScalar get() = RawScalar("String", this)
        val LocalDate.scalar: RawScalar get() = RawScalar("Date", this)
        val Instant.scalar: RawScalar get() = RawScalar("DateTime", this)
        val LocalTime.scalar: RawScalar get() = RawScalar("Time", this)
        val Byte.scalar: RawScalar get() = RawScalar("Byte", this)

        // TODO: support backing data
        val String.enum: RawEnum get() = RawEnum(this)

        fun enum(valueName: String): RawEnum = RawEnum(valueName)

        fun input(vararg pairs: Pair<String, RawValue>): RawInput = RawInput(pairs.toList())

        fun list(vararg values: RawValue): RawList = RawList(values.toList())

        fun obj(
            typename: String,
            vararg pairs: Pair<String, RawValue>
        ): RawObject = RawObject(typename, pairs.toList())

        /** Run the supplied function if this value is not [RawINull] */
        fun <T> RawValue.ifNotINull(fn: (RawValue) -> T): T? = this.takeIf { it != RawINull }?.let(fn)

        /** true if this value is [RawINull] or [RawENull] */
        val RawValue.nullish: Boolean
            get() = this == RawENull || this == RawINull
    }

    companion object : DSL()
}

/**
 * An "enull" is an explicitly null value. It can be thought of as a literal `null` that
 * can be included in graphql arguments, a graphql default value, an objects backing map,
 * a list value, etc.
 *
 * For input values, output values, and list values, an enull is only allowed for nullable
 * types.
 */
object RawENull : RawValue {
    override fun toString(): String = "ENULL"
}

/**
 * An "inull" is an implicitly null value. Unlike an enull which is represented by an explicit
 * `null` value, a materialized inull value is typically represented by the absence of a field key
 * in an input or object value, or a missing element in a list.
 *
 * For input fields, an inull is only allowed where a default value is defined
 * or when the expected type is nullable.
 *
 * For output field values, an inull is always allowed to replace a value, even when the field type is
 * nullable. This matches the behavior of output value resolution, which is driven by selection sets
 * which may always omit a field selection.
 *
 * For lists, an inull is always allowed to replace a list element, even a non-nullable one.
 */
object RawINull : RawValue {
    override fun toString(): String = "INULL"
}

sealed interface RawRecord {
    val values: List<Pair<String, RawValue>>
}

@JvmInline value class RawInput(
    override val values: List<Pair<String, RawValue>>
) : RawValue, RawRecord {
    override fun toString(): String = values.toString()
}

data class RawObject(val typename: String, override val values: List<Pair<String, RawValue>>) : RawValue, RawRecord {
    override fun toString(): String = "<$typename> $values"

    operator fun plus(entry: Pair<String, RawValue>): RawObject = copy(values = values + entry)

    companion object {
        fun empty(typename: String): RawObject = RawObject(typename, emptyList())
    }
}

data class RawScalar(val typename: String, val value: Any?) : RawValue {
    init {
        require(value !is RawValue) {
            "Cannot wrap a RawValue in RawScalar"
        }
    }

    override fun toString(): String = value.toString()
}

@JvmInline value class RawEnum(val valueName: String) : RawValue {
    override fun toString(): String = valueName
}

@JvmInline value class RawList(val values: List<RawValue>) : RawValue {
    override fun toString(): String = values.toString()

    companion object {
        val empty: RawList = RawList(emptyList())
    }
}
