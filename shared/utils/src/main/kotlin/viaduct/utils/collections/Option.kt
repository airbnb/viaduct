package viaduct.utils.collections

/**
 * A monadic interface for optional data.
 * [Option] is similar to [java.util.Optional], but allows `Some(null)` values.
 *
 * This property makes Option useful for modelling tristate values, such as
 * missing (`None`), null (`Some(null)`), and value (`Some("foo")`)
 */
sealed interface Option<out T> {
    /** return the current value if this Option is not empty, otherwise null */
    fun getOrNull(): T?

    /** get the current value if this Option is not empty, otherwise throws NoSuchElementException */
    fun get(): T

    /** return true if this Option is a [Some], otherwise false */
    fun isEmpty() = this == None

    /** transform this Option if it is not-empty */
    fun <U> map(fn: (T) -> U): Option<U>

    /** transform this Option if it is not-empty */
    fun <U> flatMap(fn: (T) -> Option<U>): Option<U>

    /** transform this Option to a List */
    fun toList(): List<T>

    /** Apply the provided function to the current value if this Option is not empty */
    fun forEach(fn: (T) -> Unit): Unit

    operator fun contains(value: Any?): Boolean =
        if (this == None) {
            false
        } else {
            get() == value
        }

    companion object {
        /** return a [Some] if [value] is non-null, otherwise [None] */
        operator fun <T> invoke(value: T?): Option<T> =
            if (value == null) {
                None
            } else {
                Some(value)
            }
    }
}

/** An empty instance of [Option] */
object None : Option<Nothing> {
    override fun get() = throw NoSuchElementException()

    override fun getOrNull(): Nothing? = null

    override fun <U> map(fn: (Nothing) -> U): None = this

    override fun <U> flatMap(fn: (Nothing) -> Option<U>): None = this

    override fun toList(): List<Nothing> = emptyList()

    override fun forEach(fn: (Nothing) -> Unit) {}
}

/** A non-empty instance of [Option] */
@JvmInline
value class Some<T>(val value: T) : Option<T> {
    override fun getOrNull(): T? = get()

    override fun get(): T = value

    override fun <U> map(fn: (T) -> U): Option<U> = Some(fn(value))

    override fun <U> flatMap(fn: (T) -> Option<U>): Option<U> = fn(value)

    override fun toList(): List<T> = listOf(value)

    override fun forEach(fn: (T) -> Unit) = fn(value)
}
