package viaduct.engine.api

/**
 * Options for resolving a root field reference.
 *
 * @property attribution Attribution applied to query planning and execution observability.
 */
data class ResolveRootFieldReferenceOptions(
    val attribution: ExecutionAttribution = ExecutionAttribution.DEFAULT,
) {
    companion object {
        val DEFAULT = ResolveRootFieldReferenceOptions()
    }
}
