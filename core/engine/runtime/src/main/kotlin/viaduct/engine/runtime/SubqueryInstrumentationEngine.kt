package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext

/**
 * Resolves a subquery selection set, threading a [ResolverInstrumentationContext] through
 * materialization so per-selection resolver instrumentation fires for the resolved fields.
 */
interface SubqueryInstrumentationEngine {
    suspend fun resolveSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
        instrumentationContext: ResolverInstrumentationContext?,
    ): EngineObjectData.Sync
}
