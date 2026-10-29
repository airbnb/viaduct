package viaduct.engine.api.instrumentation.resolver

/** Carries resolver instrumentation and its state through execution and materialization. */
class ResolverInstrumentationContext(
    val instrumentation: ViaductResolverInstrumentation,
    val state: ViaductResolverInstrumentation.InstrumentationState
)
