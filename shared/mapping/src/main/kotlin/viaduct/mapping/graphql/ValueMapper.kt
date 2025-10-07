package viaduct.mapping.graphql

/** A generic mapping function that can use a [Type] to map a [Value] from one domain to another */
interface ValueMapper<in Type, From, To> : Function2<Type, From, To> {
    // Enables function composition by chaining two ValueMappers together. It applies the first mapper (a), then feeds that result to the second mapper (b).
    private class Map<AType, BType, From, To, NewTo>(
        val a: ValueMapper<AType, From, To>,
        val b: ValueMapper<BType, To, NewTo>
    ) : ValueMapper<AType, From, NewTo> {
        @Suppress("UNCHECKED_CAST")
        override fun invoke(
            type: AType,
            v: From
        ): NewTo = b(type as BType, a(type, v))
    }

    // A simple wrapper that converts a regular function into a ValueMapper.
    private class Fn<Type, From, To>(val fn: Function2<Type, From, To>) : ValueMapper<Type, From, To> {
        override fun invoke(
            type: Type,
            from: From
        ): To = fn(type, from)
    }

    // Creates a composed mapper that applies this mapper first, then the provided mapper.
    fun <NewTo, BT : Type> map(other: ValueMapper<BT, To, NewTo>): ValueMapper<Type, From, NewTo> = Map(this, other)

    companion object {
        // Factory method to create a ValueMapper from a lambda function.
        fun <Type, From, To> mk(fn: (Type, From) -> To): ValueMapper<Type, From, To> = Fn(fn)
    }
}
