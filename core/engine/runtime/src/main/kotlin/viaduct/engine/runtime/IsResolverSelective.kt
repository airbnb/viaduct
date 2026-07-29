package viaduct.engine.runtime

import viaduct.engine.api.Coordinate

/**
 * Determines whether a resolver's result varies based on the requested subselections.
 */
fun interface IsResolverSelective {
    /** Returns true when the resolver at [coord] is selective. */
    operator fun invoke(coord: Coordinate): Boolean

    /** Returns true when either this predicate or [other] returns true. */
    infix fun or(other: IsResolverSelective): IsResolverSelective = IsResolverSelective { coord -> this(coord) || other(coord) }

    companion object {
        /** An instance of [IsResolverSelective] that never returns true. */
        val Never: IsResolverSelective = const(false)

        /** An instance of [IsResolverSelective] that always returns true. */
        val Always: IsResolverSelective = const(true)

        /** Creates an [IsResolverSelective] that always returns [value]. */
        fun const(value: Boolean): IsResolverSelective = IsResolverSelective { _ -> value }

        /** Creates an [IsResolverSelective] backed by [dispatcherRegistry]. */
        fun fromRegistry(dispatcherRegistry: DispatcherRegistry): IsResolverSelective = FromRegistry(dispatcherRegistry)
    }

    private class FromRegistry(private val registry: DispatcherRegistry) : IsResolverSelective {
        override fun invoke(coord: Coordinate): Boolean {
            val dispatcher = registry.getFieldResolverDispatcher(coord.first, coord.second)
            return dispatcher?.isSelective == true
        }
    }
}
