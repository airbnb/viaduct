package viaduct.engine.runtime.observability

import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ResolverMetadata

/**
 * Context for observability.
 * This context is used to provide information about the execution that can be used by instrumentations (for tracing or metrics).
 *
 * @property attribution The attribution of the current execution.
 * @property resolverMetadata Metadata of the resolver that resolved the field
 */
data class ExecutionObservabilityContext(
    val attribution: ExecutionAttribution? = null,
    val resolverMetadata: ResolverMetadata? = null
)
