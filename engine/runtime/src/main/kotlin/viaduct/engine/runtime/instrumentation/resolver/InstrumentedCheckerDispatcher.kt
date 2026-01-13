package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.CheckerDispatcher

/**
 * Wraps [CheckerDispatcher] to add instrumentation callbacks during checker execution.
 *
 * Delegates all operations to [dispatcher] but wraps its executor with [InstrumentedCheckerExecutor]
 * to add instrumentation state tracking and observability during checker execution lifecycle.
 */
class InstrumentedCheckerDispatcher(
    private val dispatcher: CheckerDispatcher,
    instrumentation: ViaductResolverInstrumentation
) : CheckerDispatcher by dispatcher {
    override val executor: CheckerExecutor = InstrumentedCheckerExecutor(
        dispatcher.executor,
        instrumentation
    )
}
