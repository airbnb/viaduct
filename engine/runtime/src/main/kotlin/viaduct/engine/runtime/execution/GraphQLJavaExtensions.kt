package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherResult
import graphql.execution.ExecutionContext
import graphql.execution.FetchedValue
import kotlinx.coroutines.withContext
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.DispatcherLocalContext
import viaduct.engine.runtime.context.getLocalContextForType

suspend inline fun <T> ExecutionContext.executeWithDispatcher(crossinline block: suspend () -> T): T {
    val dispatcherLocalContext = this.executionInput.getLocalContextForType<DispatcherLocalContext>() ?: return block()
    return withContext(dispatcherLocalContext.dispatcher) {
        block()
    }
}

private val Any?.asCompositeLocalContext: CompositeLocalContext
    get() = when (val ctx = this) {
        null -> CompositeLocalContext.empty
        is CompositeLocalContext -> ctx
        else ->
            throw IllegalStateException("Expected CompositeLocalContext but found ${ctx::class}")
    }

/** returns `localContext` as a CompositeLocalContext */
val DataFetcherResult<*>.compositeLocalContext: CompositeLocalContext get() = localContext.asCompositeLocalContext

/** returns `localContext` as a CompositeLocalContext */
val FetchedValue.compositeLocalContext: CompositeLocalContext get() = localContext.asCompositeLocalContext
