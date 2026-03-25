package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi

/**
 * A wrapper around a node that also carries a `cursor` encoding the item's position in the list.
 * Clients pass the cursor back as `after`/`before` to resume pagination from that point.
 *
 * [viaduct.api.internal.ConnectionBuilder.fromSlice] and [viaduct.api.internal.ConnectionBuilder.fromList]
 * set `node` and `cursor` automatically. Use [viaduct.api.internal.ConnectionBuilder.fromEdges]
 * when your edge type has additional custom fields.
 *
 * @param N The type of node this edge contains.
 * @see Connection
 */
@ExperimentalApi
interface Edge<N> : Object
