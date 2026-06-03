package viaduct.engine.runtime.execution

import viaduct.engine.api.RequiredSelectionSet

typealias QueryPlanIndex = Index<RequiredSelectionSet.Id, QueryPlan>

fun Index.Builder<RequiredSelectionSet.Id, QueryPlan>.add(plan: QueryPlan): Index.Builder<RequiredSelectionSet.Id, QueryPlan> = add(plan.index)

fun Index.Builder<RequiredSelectionSet.Id, QueryPlan>.addAll(plans: Iterable<QueryPlan>): Index.Builder<RequiredSelectionSet.Id, QueryPlan> {
    for (plan in plans) {
        add(plan)
    }
    return this
}

/** Returns a single index containing the indexes of each query plan in this iterable. */
fun Iterable<QueryPlan>.flattenIndex(): QueryPlanIndex = Index.Builder<RequiredSelectionSet.Id, QueryPlan>().addAll(this).build()
