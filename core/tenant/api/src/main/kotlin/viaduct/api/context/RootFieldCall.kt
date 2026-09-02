package viaduct.api.context

import viaduct.api.reflect.RootObjectField
import viaduct.api.types.Arguments
import viaduct.api.types.Object
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi

/**
 * Describes a root field and the arguments used to call it.
 *
 * Generated root field functions return this value without executing the field. A resolver passes
 * it to [ResolverExecutionContext.ref] to create a lazy reference that the engine resolves later.
 */
@ExperimentalApi
interface RootFieldCall<T : Object> {
    @InternalApi
    fun field(): RootObjectField<*, T, Arguments>

    /** Builds the arguments the field is called with, using [context] to convert input values. */
    @InternalApi
    fun arguments(context: ExecutionContext): Arguments
}
