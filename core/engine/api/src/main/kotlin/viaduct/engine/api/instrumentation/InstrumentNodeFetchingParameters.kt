package viaduct.engine.api.instrumentation

import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ResolverMetadata

/**
 * Parameters passed to [ViaductModernInstrumentation.WithBeginNodeFetching.beginNodeFetching].
 */
class InstrumentNodeFetchingParameters(
    val requiredBy: ExecutionAttribution?,
    /** Metadata of the node resolver that will fetch this object's data. */
    val resolverMetadata: ResolverMetadata?,
)
