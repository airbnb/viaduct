package viaduct.engine.runtime

import graphql.schema.DataFetchingEnvironment

/**
 * Determines whether a QueryPlan should be executed at runtime.
 *
 * This functional interface allows QueryPlans to carry runtime execution conditions
 * that are evaluated when the plan is about to execute. This is useful for features
 * that need dynamic control over plan execution without invalidating cached QueryPlans.
 */
fun interface QueryPlanExecutionCondition {
    /**
     * Returns true if the QueryPlan should be executed based on the given environment, false otherwise.
     *
     * This is the primary method to implement for environment-based execution conditions.
     *
     * @param env the DataFetchingEnvironment to check conditions against, may be null
     */
    fun shouldExecute(env: DataFetchingEnvironment?): Boolean

    companion object {
        /**
         * An execution condition that always returns true - the default for most QueryPlans.
         */
        val ALWAYS_EXECUTE = QueryPlanExecutionCondition { _ -> true }
    }
}
