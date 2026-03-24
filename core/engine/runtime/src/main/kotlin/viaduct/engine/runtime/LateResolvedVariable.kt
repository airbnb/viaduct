package viaduct.engine.runtime

/**
 * An interface for late resolved variables.
 *
 * This interface defines a single function `resolve` which is a suspending function,
 * meaning it can be paused and resumed at a later time without blocking a thread.
 * This function is used to resolve variables at a later stage in the data fetching environment.
 */
interface LateResolvedVariable {
    /**
     * Resolve the variable in the given data fetching environment.
     *
     * @param dataFetchingEnvironment The environment in which the data is fetched.
     * @return The resolved variable, or null if it could not be resolved.
     */
    suspend fun resolve(): Any?
}
