package viaduct.api.context

import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.apiannotations.StableApi

/** A generic context for resolvers or variable providers */
@StableApi
interface ExecutionContext {
    /**
     * Creates a GlobalID. Example usage:
     *   globalIDFor(User.Reflection, "123")
     */
    fun <T : NodeObject> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T>

    /**
     * Returns value set as [ExecutionInput.requestContext]
     */
    val requestContext: Any?
}
