package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.InternalEngineExecutionContext

/**
 * Wraps [EngineExecutionContextImpl] to intercept [resolveSelectionSet] and wrap the returned
 * [EngineObjectData.Sync] with [InstrumentedEngineObjectData.Sync], ensuring fetch-selection
 * callbacks fire for objects resolved via subquery execution.
 *
 * All other [viaduct.engine.api.EngineExecutionContext] members are delegated to [impl].
 */
internal class InstrumentedEngineExecutionContext(
    override val impl: EngineExecutionContextImpl,
    private val resolverInstrumentation: ViaductResolverInstrumentation,
    private val instrumentationState: ViaductResolverInstrumentation.InstrumentationState,
) : InternalEngineExecutionContext by impl {
    override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync =
        InstrumentedEngineObjectData.Sync(
            impl.resolveSelectionSet(selectionSet, options),
            resolverInstrumentation,
            instrumentationState,
        )
}
