package viaduct.engine.api

/**
 * Determines whether a QueryPlan should be executed at runtime.
 *
 * This functional interface allows QueryPlans to carry runtime execution conditions
 * that are evaluated when the plan is about to execute. This is useful for features
 * that need dynamic control over plan execution without invalidating cached QueryPlans.
 */
fun interface QueryPlanExecutionCondition {
    /**
     * Returns true if the QueryPlan should be executed, false otherwise.
     */
    fun shouldExecute(): Boolean

    companion object {
        /**
         * An execution condition that always returns true - the default for most QueryPlans.
         */
        val ALWAYS_EXECUTE = QueryPlanExecutionCondition { true }
    }
}
