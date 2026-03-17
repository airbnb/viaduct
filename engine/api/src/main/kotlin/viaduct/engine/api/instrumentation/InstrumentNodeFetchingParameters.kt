package viaduct.engine.api.instrumentation

import graphql.schema.DataFetchingEnvironment
import java.util.function.Supplier
import viaduct.engine.api.ResolverMetadata

/**
 * Parameters passed to [ViaductModernInstrumentation.WithBeginNodeFetching.beginNodeFetching].
 */
class InstrumentNodeFetchingParameters(
    val dataFetchingEnvironment: Supplier<DataFetchingEnvironment>,
    /** Metadata of the node resolver that will fetch this object's data. */
    val resolverMetadata: ResolverMetadata?,
)
