package semantics.resolvers

import model.SelectionForest
import semantics.shared.Demand
import semantics.shared.plus

/** Construction demand rooted at the two object results owned by one orchestration task. */
internal class OrchestratorConstructionDemand<out S : SelectionForest>(
    val objectRooted: Demand<S>,
    val queryRooted: Demand<S>,
) {
    companion object {
        /** Ordinary operation demand starts checked on the containing object occurrence. */
        fun checkedObject(selections: SelectionForest): OrchestratorConstructionDemand<SelectionForest> =
            OrchestratorConstructionDemand(
                objectRooted = Demand.checked(selections),
                queryRooted = Demand.EMPTY,
            )
    }
}

/** Adds demand independently across both root locations and both checking provenances. */
internal operator fun OrchestratorConstructionDemand<SelectionForest>.plus(
    other: OrchestratorConstructionDemand<SelectionForest>,
): OrchestratorConstructionDemand<SelectionForest> =
    OrchestratorConstructionDemand(
        objectRooted = objectRooted + other.objectRooted,
        queryRooted = queryRooted + other.queryRooted,
    )
