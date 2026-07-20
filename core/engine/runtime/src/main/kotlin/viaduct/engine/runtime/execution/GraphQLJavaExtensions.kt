package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherResult
import graphql.execution.ExecutionContext
import graphql.execution.FetchedValue
import graphql.execution.instrumentation.InstrumentationContext
import graphql.schema.GraphQLFieldDefinition
import kotlinx.coroutines.withContext
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.context.DispatcherLocalContext
import viaduct.engine.runtime.context.getLocalContextForType
import viaduct.graphql.utils.DefaultSchemaFactory

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

fun GraphQLFieldDefinition.isParentField(): Boolean = hasAppliedDirective(DefaultSchemaFactory.DefaultDirective.PARENT.directiveName)

/**
 * Completes a GraphQL Java [InstrumentationContext] with a nullable result.
 *
 * GraphQL Java declares the `onCompleted` result parameter as `@Nullable T`, but Kotlin and
 * IntelliJ still enforce the non-null generic bound on direct calls. Keep that interop in one
 * place so callers can pass the nullable results produced by field resolution and completion.
 */
fun InstrumentationContext<*>?.onCompletedNullable(
    result: Any?,
    throwable: Throwable?,
) {
    @Suppress("UNCHECKED_CAST")
    (this as? InstrumentationContext<Any?>)?.onCompleted(result, throwable)
}
