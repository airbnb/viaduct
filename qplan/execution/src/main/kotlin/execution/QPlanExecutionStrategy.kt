package execution

import graphql.ExecutionResult
import graphql.execution.AsyncExecutionStrategy
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionId
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.incremental.DelayedIncrementalPartialResult
import graphql.incremental.IncrementalExecutionResult
import graphql.incremental.IncrementalExecutionResultImpl
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import model.Assumptions
import model.selectionsFrom
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import semantics.resolver26.startResolve
import semantics.shared.ResolverObserver
import semantics.shared.SharedOperationContext

/**
 * Query execution boundary for the qplan GraphQL-Java harness.
 *
 * Each request decodes its validated operation into qplan selections, starts Resolver26, and
 * delegates GraphQL completion with a live promise-backed OER as the root source.
 * The embedding service owns [resolverCoroutineContext] for its service lifetime; this strategy
 * borrows and retains the context across requests without closing it.
 * [QPlanInstrumentation] must be installed on the enclosing `GraphQL` instance so incremental
 * publisher termination owns final request cleanup.
 */
class QPlanExecutionStrategy(
    private val world: Assumptions,
    private val resolverCoroutineContext: CoroutineContext,
    dataFetcherExceptionHandler: DataFetcherExceptionHandler =
        SimpleDataFetcherExceptionHandler(),
) : AsyncExecutionStrategy(dataFetcherExceptionHandler) {
    override fun execute(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters,
    ): CompletableFuture<ExecutionResult> {
        // Query Planniing: Convert operation to be executed into a [SelectionForest]
        val selections =
            world.selectionsFrom(
                operation = executionContext.operationDefinition,
                variables = executionContext.coercedVariables,
                graphQLContext = executionContext.graphQLContext,
                locale = executionContext.locale,
                fragmentsByName = executionContext.fragmentsByName,
            )

        val requestJob = Job()
        val requestFailure = CompletableFuture<Throwable>()
        val requestScope =
            CoroutineScope(
                resolverCoroutineContext +
                    requestJob +
                    CoroutineExceptionHandler { _, throwable ->
                        requestFailure.complete(throwable)
                    },
            )
        val lifetimeKey = QPlanRequestLifetimeKey(executionContext.executionId)
        executionContext.graphQLContext.put(
            lifetimeKey,
            QPlanRequestLifetime(requestJob),
        )

        val root =
            try {
                SharedOperationContext.create(
                    world,
                    resolverObserver = executionContext.graphQLContext.getOrDefault(
                        ResolverObserver::class.java,
                        ResolverObserver.NOP,
                    ),
                ).startResolve(selections, requestScope)
            } catch (throwable: Exception) {
                executionContext.graphQLContext.delete(lifetimeKey)
                requestJob.cancel(requestCancellation("QPlan request failed to start", throwable))
                throw throwable
            }

        val graphqlFuture =
            try {
                super.execute(
                    executionContext,
                    parameters.transform { builder ->
                        builder.source(QPlanExecutionSource(root, requestScope))
                    },
                )
            } catch (throwable: Exception) {
                executionContext.graphQLContext.delete(lifetimeKey)
                requestJob.cancel(requestCancellation("GraphQL completion failed", throwable))
                throw throwable
            }
        val resultFuture = CompletableFuture<ExecutionResult>()
        graphqlFuture.whenComplete { result, throwable ->
            val resolverFailure = requestFailure.getNow(null)
            if (resolverFailure != null) {
                executionContext.graphQLContext.delete(lifetimeKey)
                resultFuture.completeExceptionally(resolverFailure)
            } else if (throwable != null) {
                executionContext.graphQLContext.delete(lifetimeKey)
                requestJob.cancel(requestCancellation("GraphQL completion failed", throwable))
                resultFuture.completeExceptionally(throwable)
            } else if (result is IncrementalExecutionResult) {
                executionContext.graphQLContext.delete(lifetimeKey)
                resultFuture.complete(result.withRequestLifetime(requestJob))
            } else {
                resultFuture.complete(result)
                if (!executionContext.incrementalCallState.incrementalCallsDetected) {
                    executionContext.graphQLContext.delete(lifetimeKey)
                    requestJob.cancel(requestCancellation("GraphQL request completed"))
                }
            }
        }
        requestFailure.thenAccept { throwable ->
            executionContext.graphQLContext.delete(lifetimeKey)
            resultFuture.completeExceptionally(throwable)
        }
        resultFuture.whenComplete { _, _ ->
            if (resultFuture.isCancelled) {
                graphqlFuture.cancel(true)
                requestJob.cancel(requestCancellation("GraphQL execution future cancelled"))
            }
        }
        return resultFuture
    }
}

internal fun IncrementalExecutionResult.withRequestLifetime(requestJob: Job): IncrementalExecutionResult {
    val original = incrementalItemPublisher
    val wrapped =
        Publisher<DelayedIncrementalPartialResult> { downstream ->
            try {
                original.subscribe(
                    object : Subscriber<DelayedIncrementalPartialResult> {
                        override fun onSubscribe(subscription: Subscription) {
                            downstream.onSubscribe(
                                object : Subscription {
                                    override fun request(count: Long) = subscription.request(count)

                                    override fun cancel() {
                                        try {
                                            subscription.cancel()
                                        } finally {
                                            requestJob.cancel(
                                                requestCancellation(
                                                    "Incremental subscriber cancelled",
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        override fun onNext(item: DelayedIncrementalPartialResult) = downstream.onNext(item)

                        override fun onError(throwable: Throwable) {
                            try {
                                downstream.onError(throwable)
                            } finally {
                                requestJob.cancel(
                                    requestCancellation(
                                        "Incremental publisher failed",
                                        throwable,
                                    ),
                                )
                            }
                        }

                        override fun onComplete() {
                            try {
                                downstream.onComplete()
                            } finally {
                                requestJob.cancel(
                                    requestCancellation("Incremental publisher completed"),
                                )
                            }
                        }
                    },
                )
            } catch (throwable: Exception) {
                requestJob.cancel(
                    requestCancellation("Incremental publisher subscription failed", throwable),
                )
                throw throwable
            }
        }
    return IncrementalExecutionResultImpl
        .fromIncrementalExecutionResult(this)
        .incrementalItemPublisher(wrapped)
        .build()
}

internal fun requestCancellation(
    message: String,
    cause: Throwable? = null,
): CancellationException =
    CancellationException(message).also { cancellation ->
        if (cause != null) cancellation.initCause(cause)
    }

internal data class QPlanRequestLifetime(
    val requestJob: Job,
)

internal data class QPlanRequestLifetimeKey(
    val executionId: ExecutionId,
)
