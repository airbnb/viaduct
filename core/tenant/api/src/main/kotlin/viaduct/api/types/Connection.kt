package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi

/**
 * A paginated list of [Edge]s plus a `pageInfo` object describing page boundaries.
 *
 * Declare the type in your `.graphqls` schema — codegen generates the implementation and a
 * builder extending [viaduct.api.internal.ConnectionBuilder] with [viaduct.api.internal.ConnectionBuilder.fromSlice],
 * [viaduct.api.internal.ConnectionBuilder.fromList], and [viaduct.api.internal.ConnectionBuilder.fromEdges].
 *
 * @param E The edge type wrapping nodes of type [N] and carrying a per-item cursor.
 * @param N The node type returned by each edge.
 * @see Edge
 * @see viaduct.api.internal.ConnectionBuilder
 */
@ExperimentalApi
interface Connection<E : Edge<N>, N> : Object
