package viaduct.api.types

import viaduct.apiannotations.ExperimentalApi

/**
 * Represents an edge in a GraphQL Relay-style connection.
 *
 * An edge wraps a node and typically provides additional metadata
 * such as a cursor for pagination.
 *
 * @param N The type of node this edge contains.
 * @see Connection
 */
@ExperimentalApi
interface Edge<N> : Object
