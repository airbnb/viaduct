package viaduct.engine.runtime2.execution

import kotlinx.coroutines.supervisorScope
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.spi.FieldResolverExecutor

internal class Runtime2ScopedFieldBatchExecutor(
    private val delegate: FieldResolverExecutor,
) : FieldResolverExecutor by delegate {
    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext,
        instrumentationContext: ResolverInstrumentationContext?,
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> =
        // A handled Java subquery failure must not cancel other batch items.
        supervisorScope {
            delegate.batchResolve(selectors, context, instrumentationContext)
        }
}
