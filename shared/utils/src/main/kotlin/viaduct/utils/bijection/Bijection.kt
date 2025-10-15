package viaduct.utils.bijection

import viaduct.utils.collections.Option
import viaduct.utils.collections.Some

/**
 * A `Bijection<From, To>` is a pair of functions that transform an element between types
 * From and To
 * @see: https://mathworld.wolfram.com/Bijection.html
 */
interface Bijection<From, To> : Function1<From, To> {
    /** convert an instance of [From] to [To] */
    override fun invoke(from: From): To

    /** return the inverse of the current Bijection */
    fun inverse(): Bijection<To, From>

    /** convert an instance of [To] to [From], the dual of [invoke] */
    fun invert(to: To): From = inverse().invoke(to)

    /** transform this Bijection into an [Injection] */
    val asInjection: Injection<From, To> get() = InjectionAdapter(this)

    /** return a new Bijection that applies this object first, followed by `other` */
    infix fun <NewTo> andThen(other: Bijection<To, NewTo>): Bijection<From, NewTo> = AndThen(this, other)

    /** return a new Bijection that applies this object first, followed by `other` */
    fun <NewTo> andThen(
        forward: (To) -> NewTo,
        inverse: (NewTo) -> To
    ): Bijection<From, NewTo> = AndThen(this, Bijection(forward, inverse))

    /** return a new Bijection that applies `other` first, followed by this object */
    infix fun <NewFrom> compose(other: Bijection<NewFrom, From>): Bijection<NewFrom, To> = other andThen this

    private data class AndThen<LFrom, LTo, RTo>(
        private val left: Bijection<LFrom, LTo>,
        private val right: Bijection<LTo, RTo>
    ) : Bijection<LFrom, RTo> {
        override fun invoke(from: LFrom): RTo = right(left(from))

        override fun inverse(): Bijection<RTo, LFrom> = AndThen(right.inverse(), left.inverse())
    }

    private data class Impl<From, To>(
        private val forward: (From) -> To,
        private val inverse: (To) -> From
    ) : Bijection<From, To> {
        override fun invoke(from: From): To = forward(from)

        override fun inverse(): Bijection<To, From> = Impl(inverse, forward)
    }

    private data class InjectionAdapter<From, To>(private val bij: Bijection<From, To>) : Injection<From, To> {
        override fun invoke(from: From): To = bij(from)

        override fun invert(to: To): Option<From> = Some(bij.invert(to))
    }

    private object Identity : Bijection<Any?, Any?> {
        override fun invoke(value: Any?): Any? = value

        override fun inverse(): Bijection<Any?, Any?> = this
    }

    companion object {
        /** create a new Bijection from the provided functions */
        operator fun <From, To> invoke(
            forward: (From) -> To,
            inverse: (To) -> From
        ): Bijection<From, To> = Impl(forward, inverse)

        /** a Bijection that always returns its input */
        @Suppress("UNCHECKED_CAST")
        fun <T> identity(): Bijection<T, T> = Identity as Bijection<T, T>
    }
}
