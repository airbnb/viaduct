package viaduct.api.context

import viaduct.api.types.Arguments
import viaduct.apiannotations.StableApi

/**
 * Context for a VariablesProvider, providing access to the arguments and the execution context.
 * This is used to resolve variables dynamically based on the current request context.
 */
@StableApi
interface VariablesProviderContext<T : Arguments> : ExecutionContext {
    val arguments: T
}
