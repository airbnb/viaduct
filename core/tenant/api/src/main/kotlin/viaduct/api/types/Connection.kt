package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi

/**
 * Represents a GraphQL Relay-style connection for paginated data.
 *
 * Connections provide a standardized way to handle pagination,
 * containing edges and page information.
 *
 * @param E The edge type containing nodes of type [N].
 * @param N The node type contained within edges.
 * @see Edge
 * @see ConnectionArguments
 */
@ExperimentalApi
interface Connection<E : Edge<N>, N> : Object
