package viaduct.api.internal

import viaduct.api.globalid.GlobalID
import viaduct.api.types.NodeObject

/** Interface for creating a reference at an unresolved node */
interface NodeReferenceGRTFactory {
    /**
     * Returns a reference to an unresolved node value given a global ID.
     */
    fun <T : NodeObject> nodeFor(
        id: GlobalID<T>,
        internalContext: InternalContext
    ): T
}
