package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.InternalEngineExecutionContext

/**
 * Wraps [EngineExecutionContextImpl] to intercept [resolveSelectionSet] so that fetch-selection
 * instrumentation flows through subquery execution (`ctx.query()` / `ctx.mutation()`):
 * - the subquery's materialization receives a [ResolverInstrumentationContext], so per-selection
 *   [ViaductResolverInstrumentation.beginFetchSelection] callbacks fire for the resolved fields, and
 * - the returned [EngineObjectData.Sync] is wrapped with [InstrumentedEngineObjectData.Sync] so
 *   subsequent reads are instrumented too.
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
            impl.resolveSelectionSet(
                selectionSet,
                options,
                ResolverInstrumentationContext(resolverInstrumentation, instrumentationState),
            ),
            resolverInstrumentation,
            instrumentationState,
        )
}
