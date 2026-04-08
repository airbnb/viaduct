package viaduct.engine.runtime

import viaduct.engine.api.Coordinate

/**
 * Determines whether a resolver's result varies based on the requested subselections.
 */
fun interface IsResolverSelective {
    /** Returns true when the resolver at [coord] is selective. */
    operator fun invoke(coord: Coordinate): Boolean

    companion object {
        /** An instance of [IsResolverSelective] that never returns true. */
        val Never: IsResolverSelective = const(false)

        /** An instance of [IsResolverSelective] that always returns true. */
        val Always: IsResolverSelective = const(true)

        /** Creates an [IsResolverSelective] that always returns [value]. */
        fun const(value: Boolean): IsResolverSelective = IsResolverSelective { _ -> value }

        /**
         * Creates an [IsResolverSelective] backed by [dispatcherRegistry].
         *
         * A resolver coordinate is selective when the registry contains a field resolver dispatcher
         * for that coordinate and that dispatcher is marked selective.
         */
        fun fromRegistry(dispatcherRegistry: DispatcherRegistry): IsResolverSelective =
            IsResolverSelective { (typeName, fieldName) ->
                dispatcherRegistry.getFieldResolverDispatcher(typeName, fieldName)?.isSelective == true
            }
    }
}
