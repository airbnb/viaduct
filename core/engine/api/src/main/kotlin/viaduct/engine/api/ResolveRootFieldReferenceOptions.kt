package viaduct.engine.api

/**
 * Options for resolving a root field reference.
 *
 * @property attribution Attribution applied to query planning and execution observability.
 */
data class ResolveRootFieldReferenceOptions(
    val attribution: ExecutionAttribution = ExecutionAttribution.DEFAULT,
    /**
     * The resolver that created the reference.
     *
     * The engine attributes the root field to this resolver, not to the resolver that awaits the
     * reference. Null when no resolver created it.
     */
    val caller: Caller? = null,
) {
    companion object {
        val DEFAULT = ResolveRootFieldReferenceOptions()
    }
}
