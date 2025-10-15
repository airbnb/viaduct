package viaduct.utils.bijection

import viaduct.utils.collections.None
import viaduct.utils.collections.Option
import viaduct.utils.collections.Some

/**
 * An `Injection<From, To>` is a function from From to To, and from some To back to From.
 * This can be used to model a mapping between sets that is not always reversible.
 * @see: http://mathworld.wolfram.com/Injection.html
 */
interface Injection<From, To> : Function1<From, To> {
    /** Apply this Injection in the forward direction */
    override fun invoke(from: From): To

    /**
     * Attempt to apply this Injection in the inverse direction, returning
     * a [Some] if an inverse mapping is defined, or [None] otherwise.
     */
    fun invert(to: To): Option<From>

    /**
     * Return a new [Injection] that applies this object first, followed by [other].
     * The returned Injection will support inversions only if this and [other] are invertible.
     */
    infix fun <NewTo> andThen(other: Injection<To, NewTo>): Injection<From, NewTo> = AndThen(this, other)

    /**
     * Return a new [Injection] that applies this object first, followed by [other].
     * The returned Injection will never support inversions.
     */
    infix fun <NewTo> andThen(fn: (To) -> NewTo): Injection<From, NewTo> = AndThen(this, Injection(fn))

    private data class Impl<From, To>(
        private val forward: (From) -> To,
        private val inverse: (To) -> Option<From>
    ) : Injection<From, To> {
        override fun invoke(from: From): To = forward(from)

        override fun invert(to: To): Option<From> = inverse(to)
    }

    private data class AndThen<LFrom, LTo, RTo>(
        private val left: Injection<LFrom, LTo>,
        private val right: Injection<LTo, RTo>
    ) : Injection<LFrom, RTo> {
        override fun invoke(from: LFrom): RTo = right(left(from))

        override fun invert(to: RTo): Option<LFrom> =
            when (val lto = right.invert(to)) {
                is Some -> left.invert(lto.get())
                else -> None
            }
    }

    private object Identity : Injection<Any?, Any?> {
        override fun invoke(from: Any?): Any? = from

        override fun invert(to: Any?): Option<Any?> = Some(to)
    }

    companion object {
        /** Create a new [Injection] from the provided functions */
        operator fun <From, To> invoke(
            forward: (From) -> To,
            inverse: (To) -> Option<From>
        ): Injection<From, To> = Impl(forward, inverse)

        /**
         * Create a new [Injection] from the provided functions.
         * The returned [Injection] will never be invertible.
         */
        operator fun <From, To> invoke(forward: (From) -> To): Injection<From, To> = Impl(forward, { None })

        /**
         * Return an [Injection] that always returns the provided input value.
         * The returned [Injection] is always invertible.
         */
        @Suppress("UNCHECKED_CAST")
        fun <T> identity(): Injection<T, T> = Identity as Injection<T, T>
    }
}
