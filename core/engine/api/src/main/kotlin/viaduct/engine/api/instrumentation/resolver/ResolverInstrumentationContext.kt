package viaduct.engine.api.instrumentation.resolver

/**
 * Carries resolver instrumentation through fetch-selection materialization.
 *
 * Passed explicitly into [viaduct.engine.runtime.EngineObjectDataFactory.create] (and the
 * runtime's selection-set materialization) so [viaduct.engine.runtime.SyncEngineObjectDataFactory]
 * can fire per-selection instrumentation without smuggling state through the coroutine context.
 */
class ResolverInstrumentationContext(
    val instrumentation: ViaductResolverInstrumentation,
    val state: ViaductResolverInstrumentation.InstrumentationState
)
