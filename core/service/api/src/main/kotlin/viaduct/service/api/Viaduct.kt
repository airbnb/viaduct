package viaduct.service.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import viaduct.apiannotations.StableApi

/**
 * The main entry point for executing GraphQL operations against the Viaduct runtime.
 *
 * Instances are created via [ViaductBuilder][viaduct.service.ViaductBuilder] (for full SPI control) or
 * [BasicViaductFactory][viaduct.service.BasicViaductFactory] (for simpler use cases).
 * A typical integration creates a single [Viaduct] instance at startup and routes incoming
 * GraphQL requests through [execute] or [executeAsync].
 */
@StableApi
interface Viaduct {
    /**
     * Executes an operation on this Viaduct instance, suspending until it completes.
     *
     * This is the idiomatic entry point for Kotlin callers. Java callers, and callers that want to
     * control the threads on which execution runs, should use [executeAsync] instead.
     *
     * @param executionInput the execution input for this operation
     * @param schemaId the id of the schema against which to execute, defaults to [SchemaId.Full]
     * @return the [ExecutionResult] whose [errors][ExecutionResult.errors] are sorted by path
     *         then by message
     */
    suspend fun execute(
        executionInput: ExecutionInput,
        schemaId: SchemaId = SchemaId.Full
    ): ExecutionResult

    /**
     * Executes an operation on this Viaduct instance asynchronously, returning a [CompletableFuture].
     *
     * This is the idiomatic entry point for Java callers. Following the standard Java idiom, it accepts
     * an [Executor] that controls the threads on which the operation runs. The [executeAsync] overloads
     * default the executor to [ForkJoinPool.commonPool], matching [CompletableFuture.supplyAsync].
     *
     * @param executionInput the execution input for this operation
     * @param schemaId the id of the schema against which to execute
     * @param executor the executor on which to run the operation
     * @return a [CompletableFuture] of [ExecutionResult] whose [errors][ExecutionResult.errors]
     *         are sorted by path then by message
     */
    fun executeAsync(
        executionInput: ExecutionInput,
        schemaId: SchemaId,
        executor: Executor
    ): CompletableFuture<ExecutionResult>

    /**
     * Executes an operation against [SchemaId.Full] on the [ForkJoinPool.commonPool].
     *
     * @see executeAsync
     */
    fun executeAsync(executionInput: ExecutionInput): CompletableFuture<ExecutionResult> = executeAsync(executionInput, SchemaId.Full, ForkJoinPool.commonPool())

    /**
     * Executes an operation against [schemaId] on the [ForkJoinPool.commonPool].
     *
     * @see executeAsync
     */
    fun executeAsync(
        executionInput: ExecutionInput,
        schemaId: SchemaId
    ): CompletableFuture<ExecutionResult> = executeAsync(executionInput, schemaId, ForkJoinPool.commonPool())

    /**
     * Returns the set of scope IDs applied to the given [schemaId], or `null` if no
     * scopes are configured for it.
     *
     * @param schemaId the schema whose applied scopes to retrieve
     * @return the set of applied scope IDs, or `null`
     */
    fun getAppliedScopes(schemaId: SchemaId): Set<String>?
}
