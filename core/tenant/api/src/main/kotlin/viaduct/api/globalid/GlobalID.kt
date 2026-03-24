package viaduct.api.globalid

import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput
import viaduct.apiannotations.StableApi

/**
 * GlobalIDs are objects in Viaduct that contain 'type' and 'internalID' properties.
 * They are used to uniquely identify node objects in the graph.
 *
 * GlobalID values support structural equality, as opposed to referential equality.
 *
 * A GlobalID<T> will be generated for fields with the @idOf(type:"T") directive.
 *
 * Instances of GlobalID can be created using execution-context objects,
 * e.g., ExecutionContext.nodeIDFor(User, 123).
 */
@StableApi
interface GlobalID<out T : NodeCompositeOutput> {
    /** The type of the node object, e.g. User. */
    val type: Type<T>

    /** The internal ID of the node object, e.g. 123. */
    val internalID: String
}
