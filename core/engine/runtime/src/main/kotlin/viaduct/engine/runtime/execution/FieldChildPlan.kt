package viaduct.engine.runtime.execution

import graphql.schema.GraphQLCompositeType
import viaduct.engine.api.Coordinate
import viaduct.engine.api.RequiredSelectionSet

/**
 * @property requiredSelectionSetId the id of the RequiredSelectionSet that this child plan supports
 * @property queryPlanParentType the GraphQL parent type of the RSS plan.
 * @property originCoordinate identifies the field whose resolver/checker RSS produced this dependency.
 */
data class FieldChildPlan(
    val requiredSelectionSetId: RequiredSelectionSet.Id,
    val queryPlanParentType: GraphQLCompositeType,
    val originCoordinate: Coordinate,
) {
    constructor(
        plan: QueryPlan,
        originCoordinate: Coordinate,
    ) : this(
        requiredSelectionSetId = requireNotNull(plan.requiredSelectionSetId) {
            "Field child QueryPlans must be backed by a RequiredSelectionSet id"
        },
        queryPlanParentType = plan.parentType as? GraphQLCompositeType
            ?: error("Field child QueryPlans must have a composite parent type"),
        originCoordinate = originCoordinate,
    )
}
