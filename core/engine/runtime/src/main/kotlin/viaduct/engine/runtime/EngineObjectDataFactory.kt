package viaduct.engine.runtime

import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext

/**
 * Creates an [EngineObjectData.Sync], optionally supplying a [ResolverInstrumentationContext]
 * for fetch-selection observability. Pass `null` for uninstrumented execution.
 */
fun interface EngineObjectDataFactory {
    suspend fun create(resolverInstrumentationContext: ResolverInstrumentationContext?): EngineObjectData.Sync
}
