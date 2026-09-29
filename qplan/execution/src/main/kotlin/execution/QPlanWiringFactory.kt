package execution

import graphql.ExceptionWhileDataFetching
import graphql.TypeResolutionEnvironment
import graphql.execution.DataFetcherResult
import graphql.execution.ResultPath
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import graphql.schema.TypeResolver
import graphql.schema.idl.FieldWiringEnvironment
import graphql.schema.idl.InterfaceWiringEnvironment
import graphql.schema.idl.UnionWiringEnvironment
import graphql.schema.idl.WiringFactory
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import model.EngineErrorData
import model.EngineIDResult
import model.EngineResult
import model.EngineResultCell
import model.EngineResultIsPending
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.Promise
import model.SourceSchemaAdapter
import model.awaitCheckedValue
import model.materializeCheckedValue
import viaduct.graphql.schema.ViaductSchema

/**
 * GraphQL-Java completion wiring backed by a live qplan result tree.
 *
 * The execution strategy supplies a [QPlanExecutionSource] as the root source. Nested object values
 * retain the same request scope so pending qplan promises can be exposed as completion stages.
 */
class QPlanWiringFactory(
    sourceSchema: SourceSchemaAdapter,
) : WiringFactory {
    private val dataFetcher = ObjectEngineResultDataFetcher(sourceSchema)

    override fun getDefaultDataFetcher(environment: FieldWiringEnvironment): DataFetcher<*> = dataFetcher

    override fun providesTypeResolver(environment: InterfaceWiringEnvironment): Boolean = true

    override fun getTypeResolver(environment: InterfaceWiringEnvironment): TypeResolver = TypeResolver(::resolveType)

    override fun providesTypeResolver(environment: UnionWiringEnvironment): Boolean = true

    override fun getTypeResolver(environment: UnionWiringEnvironment): TypeResolver = TypeResolver(::resolveType)
}

/** One qplan object occurrence and the request scope that owns its pending promise bridges. */
internal data class QPlanExecutionSource(
    val objectResult: ObjectEngineResult,
    val requestScope: CoroutineScope?,
)

private class ObjectEngineResultDataFetcher(
    private val sourceSchema: SourceSchemaAdapter,
) : DataFetcher<Any?> {
    override fun get(environment: DataFetchingEnvironment): Any? {
        val source = qplanSource(environment.getSource())
        val objectResult = source.objectResult
        val sourceFieldName = environment.fieldDefinition.name
        val field = sourceSchema.field(objectResult.type.name, sourceFieldName)
        require(field is ViaductSchema.ObjectField) {
            "QPlan completion requires a concrete field for " +
                "${objectResult.type.name}/$sourceFieldName"
        }
        val key =
            ObjectEngineResult.GroundKey.of(
                field = field,
                arguments = environment.arguments,
            )
        val value =
            objectResult
                .getCell(key)
        return value.toGraphQLJavaValue(source, environment)
    }
}

private fun resolveType(environment: TypeResolutionEnvironment): GraphQLObjectType {
    val source = qplanSource(environment.getObject())
    return environment.schema.getObjectType(source.objectResult.type.name)
        ?: throw IllegalStateException(
            "GraphQL schema has no object type named ${source.objectResult.type.name}",
        )
}

private fun qplanSource(source: Any?): QPlanExecutionSource =
    when (source) {
        is QPlanExecutionSource -> source
        is ObjectEngineResult -> QPlanExecutionSource(source, requestScope = null)
        else ->
            throw IllegalStateException(
                "QPlan completion requires an ObjectEngineResult source",
            )
    }

private fun EngineResultCell.toGraphQLJavaValue(
    source: QPlanExecutionSource,
    environment: DataFetchingEnvironment,
    path: ResultPath = environment.executionStepInfo.path,
): Any? {
    val attempt = materializeCheckedValue { true }
    if (attempt !== EngineResultIsPending) {
        return attempt.toGraphQLJavaValue(source, environment, path)
    }
    val requestScope =
        requireNotNull(source.requestScope) {
            "Pending qplan values require a request-owned coroutine scope"
        }
    return requestScope.asCompletableFuture(
        preferredFailure = this::preferredTerminalFailureOrNull,
    ) {
        awaitCheckedValue { true }
            .toGraphQLJavaValueAwaiting(source, environment, path)
    }
}

private fun EngineResult?.toGraphQLJavaValue(
    source: QPlanExecutionSource,
    environment: DataFetchingEnvironment,
    path: ResultPath = environment.executionStepInfo.path,
): Any? =
    when (this) {
        null -> null
        is ErrorEngineResult -> errorResult(errorData, environment, path)
        is ObjectEngineResult -> source.copy(objectResult = this)
        is ListEngineResult -> {
            if (isGraphQLJavaValueReady()) {
                mapIndexed { index, cell ->
                    val attempt = cell.materializeCheckedValue { true }
                    check(attempt !== EngineResultIsPending)
                    attempt.toGraphQLJavaValue(source, environment, path.segment(index))
                }
            } else {
                val requestScope =
                    requireNotNull(source.requestScope) {
                        "Pending qplan list values require a request-owned coroutine scope"
                    }
                requestScope.asCompletableFuture(
                    preferredFailure = this::preferredTerminalFailureOrNull,
                ) {
                    this@toGraphQLJavaValue.toGraphQLJavaValueAwaiting(
                        source,
                        environment,
                        path,
                    )
                }
            }
        }
        is EngineIDResult -> value
        is ViaductSchema.EnumValue -> name
        is Int,
        is Double,
        is Boolean,
        is String,
        -> this
        else -> throw IllegalStateException("Unexpected qplan engine result: $this")
    }

private fun EngineResult?.isGraphQLJavaValueReady(): Boolean =
    when (this) {
        is ListEngineResult ->
            all { cell ->
                val attempt =
                    try {
                        cell.materializeCheckedValue { true }
                    } catch (_: Exception) {
                        return@all false
                    }
                attempt !== EngineResultIsPending && attempt.isGraphQLJavaValueReady()
            }
        else -> true
    }

private suspend fun EngineResult?.toGraphQLJavaValueAwaiting(
    source: QPlanExecutionSource,
    environment: DataFetchingEnvironment,
    path: ResultPath,
): Any? =
    when (this) {
        null -> null
        is ErrorEngineResult -> errorResult(errorData, environment, path)
        is ObjectEngineResult -> source.copy(objectResult = this)
        is ListEngineResult -> toGraphQLJavaListAwaiting(source, environment, path)
        is EngineIDResult -> value
        is ViaductSchema.EnumValue -> name
        is Int,
        is Double,
        is Boolean,
        is String,
        -> this
        else -> throw IllegalStateException("Unexpected qplan engine result: $this")
    }

private suspend fun ListEngineResult.toGraphQLJavaListAwaiting(
    source: QPlanExecutionSource,
    environment: DataFetchingEnvironment,
    path: ResultPath,
): List<Any?> =
    coroutineScope {
        val elements =
            mapIndexed { index, cell ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    cell
                        .awaitCheckedValue { true }
                        .toGraphQLJavaValueAwaiting(source, environment, path.segment(index))
                }
            }
        try {
            elements.awaitAll()
        } catch (failure: Exception) {
            preferredTerminalFailureOrNull()?.let { throw it }
            throw failure
        }
    }

private fun Promise<*>.terminalFailureOrNull(): Exception? {
    if (!isCompleted) return null
    return try {
        get()
        null
    } catch (failure: Exception) {
        failure
    }
}

private fun EngineResultCell.preferredTerminalFailureOrNull(): Exception? {
    var cancellation: Exception? = null
    var allCheckersCompleted = true
    val checkerPromises =
        buildList {
            add(fieldCheckerResult)
            val valuePromise = value
            if (valuePromise.isCompleted) {
                val value =
                    try {
                        valuePromise.get()
                    } catch (failure: Exception) {
                        return failure
                    }
                if (value is ObjectEngineResult) {
                    add(value.typeCheckerResult)
                }
            }
        }
    checkerPromises.forEach { promise ->
        val failure = promise.terminalFailureOrNull()
        if (failure != null) {
            if (failure !is CancellationException) return failure
            if (cancellation == null) cancellation = failure
        }
        if (!promise.isCompleted) allCheckersCompleted = false
    }
    if (cancellation != null) return cancellation
    if (!allCheckersCompleted) return null
    return try {
        materializeCheckedValue { true }
        null
    } catch (failure: Exception) {
        failure
    }
}

private fun ListEngineResult.preferredTerminalFailureOrNull(): Exception? {
    var cancellation: Exception? = null
    forEach { cell ->
        val failure = cell.preferredTerminalFailureOrNull()
        if (failure != null) {
            if (failure !is CancellationException) return failure
            if (cancellation == null) cancellation = failure
            return@forEach
        }
        val attempt =
            try {
                cell.materializeCheckedValue { true }
            } catch (_: Exception) {
                return@forEach
            }
        if (attempt is ListEngineResult) {
            val nestedFailure = attempt.preferredTerminalFailureOrNull()
            if (nestedFailure != null) {
                if (nestedFailure !is CancellationException) return nestedFailure
                if (cancellation == null) cancellation = nestedFailure
            }
        }
    }
    return cancellation
}

internal fun <T> CoroutineScope.asCompletableFuture(
    preferredFailure: () -> Exception? = { null },
    block: suspend () -> T,
): CompletableFuture<T> {
    val result = CompletableFuture<T>()
    val task =
        launch {
            try {
                result.complete(block())
            } catch (cause: Exception) {
                try {
                    currentCoroutineContext().ensureActive()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                }
                result.completeExceptionally(preferredFailure() ?: cause)
            }
        }
    task.invokeOnCompletion { cause ->
        if (cause is CancellationException) {
            result.completeExceptionally(CoroutineCancellationBridgeException(cause))
        }
    }
    result.whenComplete { _, _ ->
        if (result.isCancelled && task.isActive) task.cancel()
    }
    return result
}

/** Prevents Kotlin coroutine cancellation from acquiring Java future-cancellation semantics. */
internal class CoroutineCancellationBridgeException(
    cause: CancellationException,
) : RuntimeException("QPlan coroutine was cancelled", cause)

private fun errorResult(
    errorData: EngineErrorData,
    environment: DataFetchingEnvironment,
    path: ResultPath,
): DataFetcherResult<Any> =
    DataFetcherResult
        .newResult<Any>()
        .error(
            ExceptionWhileDataFetching(
                path,
                errorData.cause ?: QPlanFieldResolutionException(),
                environment.field.sourceLocation,
            ),
        ).build()

private class QPlanFieldResolutionException :
    RuntimeException("QPlan field resolution failed")
