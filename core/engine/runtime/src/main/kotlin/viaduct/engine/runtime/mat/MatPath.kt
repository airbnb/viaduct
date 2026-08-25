package viaduct.engine.runtime.mat

import graphql.schema.GraphQLObjectType
import viaduct.engine.runtime.result.ObjectEngineResult

/**
 * A path from a directly materialized object to the object currently being read.
 *
 * @param rootType is the concrete type of the object that owns the mat ledger.
 * @param segments is the list of field hops from that root object to the current object.
 *   If [segments] is empty, the current object is the root object.
 */
data class MatPath(
    val rootType: GraphQLObjectType,
    val segments: List<Segment> = emptyList(),
) {
    /**
     * One hop in a [MatPath].
     *
     * The parent object type is [MatPath.rootType] for the first segment, or the previous
     * segment's [type] for later segments.
     *
     * @param type is the concrete object type reached after reading [key].
     * @param key identifies the field instance read from the parent object.
     * @param indices are list indices that locate the reached object when [key] returns
     *   nested lists.
     */
    data class Segment(
        val type: GraphQLObjectType,
        val key: ObjectEngineResult.Key,
        val indices: List<Int> = emptyList(),
    )

    /** The concrete object type of the object currently being read. */
    val terminalType: GraphQLObjectType get() = segments.lastOrNull()?.type ?: rootType
}
