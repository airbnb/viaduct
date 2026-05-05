package viaduct.api.globalid

import kotlin.reflect.full.isSubclassOf
import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.apiannotations.InternalApi

/**
 * Default implementation of GlobalID.
 *
 * GlobalIDImpl is a data class that represents a unique identifier for a node object
 * in the Viaduct graph. It contains the type information and internal ID.
 */
@InternalApi
data class GlobalIDImpl<T : NodeCompositeOutput>(
    override val type: Type<T>,
    override val internalID: String,
) : GlobalID<T> {
    init {
        require(type.kcls.isSubclassOf(NodeObject::class)) { "GlobalID type must be a NodeObject" }
    }
}
