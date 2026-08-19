package viaduct.api.context

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
interface RootFieldCall<out T : Object> {
    @InternalApi
    fun resolve(context: ResolverExecutionContext<*>): T
}
