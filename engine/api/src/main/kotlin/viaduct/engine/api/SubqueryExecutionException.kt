package viaduct.engine.api

/**
 * Exception thrown when subquery execution fails.
 *
 * This exception wraps underlying errors from QueryPlan building or field resolution
 * during ctx.query() or ctx.mutation() calls from within resolvers.
 */
class SubqueryExecutionException(
    message: String,
    override val cause: Throwable? = null
) : RuntimeException(message, cause) {
    companion object {
        fun queryPlanBuildFailed(cause: Throwable): SubqueryExecutionException = SubqueryExecutionException("Failed to build QueryPlan for subquery execution", cause)

        fun fieldResolutionFailed(cause: Throwable): SubqueryExecutionException = SubqueryExecutionException("Failed to resolve fields during subquery execution", cause)

        fun invalidExecutionHandle(): SubqueryExecutionException = SubqueryExecutionException("ExecutionHandle does not contain valid ExecutionParameters for subquery execution")
    }
}
