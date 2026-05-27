package viaduct.engine.runtime.execution

import viaduct.engine.api.Coordinate

/**
 * A QueryPlan attached to a specific (parentType, fieldName) coordinate during planning.
 *
 * The [originCoordinate] identifies the field whose resolver/checker RSS produced this plan.
 * It is used by CollectFields to drop plans whose origin coordinate does not match the
 * concrete field currently being collected.
 */
data class FieldChildPlan(
    val plan: QueryPlan,
    val originCoordinate: Coordinate,
)
