package semantics.shared

import model.SelectionForest

/** Construction demand rooted at the two object results owned by one orchestration task. */
internal class OrchestrationConstructionDemand<out S : SelectionForest>(
    val objectRooted: Demand<S>,
    val queryRooted: Demand<S>,
) {
    companion object {
        /** Ordinary operation demand starts checked on the containing object occurrence. */
        fun checkedObject(selections: SelectionForest): OrchestrationConstructionDemand<SelectionForest> =
            OrchestrationConstructionDemand(
                objectRooted = Demand.checked(selections),
                queryRooted = Demand.EMPTY,
            )
    }
}

/** Adds demand independently across both root locations and both checking provenances. */
internal operator fun OrchestrationConstructionDemand<SelectionForest>.plus(other: OrchestrationConstructionDemand<SelectionForest>): OrchestrationConstructionDemand<SelectionForest> =
    OrchestrationConstructionDemand(
        objectRooted = objectRooted + other.objectRooted,
        queryRooted = queryRooted + other.queryRooted,
    )
