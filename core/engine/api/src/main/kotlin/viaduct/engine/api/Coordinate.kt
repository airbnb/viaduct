package viaduct.engine.api

import graphql.schema.FieldCoordinates

/**
 * A Coordinate describes a unique identifier in a schema.
 * This is a viaduct-flavored version of graphql-java's [graphql.schema.FieldCoordinates]
 *
 * The structure of this Coordinate is:
 *   1. type name
 *   2. field name
 */
typealias Coordinate = Pair<String, String>

/** Convert a [Coordinate] into an equivalent graphql-java representation */
val Coordinate.gj: FieldCoordinates get() =
    if (second.startsWith("__")) {
        FieldCoordinates.systemCoordinates(second)
    } else {
        FieldCoordinates.coordinates(first, second)
    }
