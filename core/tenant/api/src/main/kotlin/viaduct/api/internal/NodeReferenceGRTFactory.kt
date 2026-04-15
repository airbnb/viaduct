package viaduct.api.internal

import viaduct.api.globalid.GlobalID
import viaduct.api.types.NodeObject
import viaduct.apiannotations.InternalApi

/** Interface for creating a reference at an unresolved node */
@InternalApi
interface NodeReferenceGRTFactory {
    /**
     * Returns a reference to an unresolved node value given a global ID.
     */
    fun <T : NodeObject> nodeRef(
        id: GlobalID<T>,
        internalContext: InternalContext
    ): T
}
