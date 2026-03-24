package viaduct.engine.runtime.instrumentation.resolver

import java.util.concurrent.ConcurrentLinkedQueue
import viaduct.engine.api.instrumentation.resolver.CheckerFunction
import viaduct.engine.api.instrumentation.resolver.FetchFunction
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.SyncFetchFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

class RecordingResolverInstrumentation : ViaductResolverInstrumentation {
    class RecordingInstrumentationState : ViaductResolverInstrumentation.InstrumentationState

    data class RecordingFetchSelectionContext(
        val parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        val result: Any?,
        val error: Throwable?
    )

    data class RecordingExecuteResolverContext(
        val parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
        val result: Any?,
        val error: Throwable?
    )

    data class RecordingExecuteCheckerContext(
        val parameters: ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters,
        val result: Any?,
        val error: Throwable?
    )

    val fetchSelectionContexts = ConcurrentLinkedQueue<RecordingFetchSelectionContext>()
    val syncFetchSelectionContexts = ConcurrentLinkedQueue<RecordingFetchSelectionContext>()
    val executeResolverContexts = ConcurrentLinkedQueue<RecordingExecuteResolverContext>()
    val executeCheckerContexts = ConcurrentLinkedQueue<RecordingExecuteCheckerContext>()

    override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
        return RecordingInstrumentationState()
    }

    override fun <T> instrumentResolverExecution(
        resolver: ResolverFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?,
    ): ResolverFunction<T> =
        ResolverFunction {
            recordExecution({ resolver.resolve() }) { result, error ->
                executeResolverContexts.add(RecordingExecuteResolverContext(parameters, result, error))
            }
        }

    override fun <T> instrumentFetchSelection(
        fetchFn: FetchFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?,
    ): FetchFunction<T> =
        FetchFunction {
            recordExecution({ fetchFn.fetch() }) { result, error ->
                fetchSelectionContexts.add(RecordingFetchSelectionContext(parameters, result, error))
            }
        }

    override fun <T> instrumentSyncFetchSelection(
        fetchFn: SyncFetchFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?,
    ): SyncFetchFunction<T> =
        SyncFetchFunction {
            recordSyncExecution({ fetchFn.fetch() }) { result, error ->
                syncFetchSelectionContexts.add(RecordingFetchSelectionContext(parameters, result, error))
            }
        }

    private suspend inline fun <T> recordExecution(
        executeFn: suspend () -> T,
        record: (result: Any?, error: Throwable?) -> Unit
    ): T {
        return try {
            val result = executeFn()
            record(result, null)
            result
        } catch (e: Throwable) {
            record(null, e)
            throw e
        }
    }

    private inline fun <T> recordSyncExecution(
        executeFn: () -> T,
        record: (result: Any?, error: Throwable?) -> Unit
    ): T {
        return try {
            val result = executeFn()
            record(result, null)
            result
        } catch (e: Throwable) {
            record(null, e)
            throw e
        }
    }

    override fun <T> instrumentAccessChecker(
        checker: CheckerFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?,
    ): CheckerFunction<T> =
        CheckerFunction {
            recordExecution({ checker.check() }) { result, error ->
                executeCheckerContexts.add(RecordingExecuteCheckerContext(parameters, result, error))
            }
        }

    fun reset() {
        fetchSelectionContexts.clear()
        syncFetchSelectionContexts.clear()
        executeResolverContexts.clear()
        executeCheckerContexts.clear()
    }
}
