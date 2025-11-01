package viaduct.mapping.graphql

/**
 * A `Conv<From, To>` is a pair of functions that transform an element between types
 * From and To
 */
interface Conv<From, To> : Function1<From, To> {
    /** convert an instance of [From] to [To] */
    override fun invoke(from: From): To

    /** return the inverse of the current Conv */
    fun inverse(): Conv<To, From>

    /** convert an instance of [To] to [From], the dual of [invoke] */
    fun invert(to: To): From = inverse().invoke(to)

    /** return a new Conv that applies this object first, followed by `other` */
    infix fun <NewTo> andThen(other: Conv<To, NewTo>): Conv<From, NewTo> = AndThen(this, other)

    /** return a new Conv that applies this object first, followed by `other` */
    fun <NewTo> andThen(
        forward: (To) -> NewTo,
        inverse: (NewTo) -> To
    ): Conv<From, NewTo> = AndThen(this, Conv(forward, inverse))

    /** return a new Conv that applies `other` first, followed by this object */
    infix fun <NewFrom> compose(other: Conv<NewFrom, From>): Conv<NewFrom, To> = other andThen this

    private data class AndThen<LFrom, LTo, RTo>(
        private val left: Conv<LFrom, LTo>,
        private val right: Conv<LTo, RTo>
    ) : Conv<LFrom, RTo> {
        override fun invoke(from: LFrom): RTo = right(left(from))

        override fun inverse(): Conv<RTo, LFrom> = AndThen(right.inverse(), left.inverse())
    }

    private data class Impl<From, To>(
        private val forward: (From) -> To,
        private val inverse: (To) -> From,
        private val name: String?
    ) : Conv<From, To> {
        override fun invoke(from: From): To = forward(from)

        override fun inverse(): Conv<To, From> = Impl(inverse, forward, name)

        override fun toString(): String = name ?: super.toString()
    }

    private object Identity : Conv<Any?, Any?> {
        override fun invoke(from: Any?): Any? = from

        override fun inverse(): Conv<Any?, Any?> = this

        override fun toString(): String = "Conv.identity"
    }

    companion object {
        /**
         * Create a new [Conv] from the provided functions
         *
         * @param name an optional name that will be used in toString, useful for debugging
         */
        operator fun <From, To> invoke(
            forward: (From) -> To,
            inverse: (To) -> From,
            name: String? = null
        ): Conv<From, To> = Impl(forward, inverse, name)

        /** a Conv that always returns its input */
        @Suppress("UNCHECKED_CAST")
        fun <T> identity(): Conv<T, T> = Identity as Conv<T, T>
    }
}
