package viaduct.service.api

import java.util.concurrent.CompletableFuture
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
     * Executes an operation on this Viaduct instance asynchronously.
     *
     * @param executionInput the execution input for this operation
     * @param schemaId the id of the schema against which to execute, defaults to [SchemaId.Full]
     * @return a [CompletableFuture] of [ExecutionResult] whose [errors][ExecutionResult.errors]
     *         are sorted by path then by message
     */
    suspend fun executeAsync(
        executionInput: ExecutionInput,
        schemaId: SchemaId = SchemaId.Full
    ): CompletableFuture<ExecutionResult>

    /**
     * Executes an operation on this Viaduct instance synchronously.
     *
     * @param executionInput the execution input for this operation
     * @param schemaId the id of the schema against which to execute, defaults to [SchemaId.Full]
     * @return the [ExecutionResult] whose [errors][ExecutionResult.errors] are sorted by path
     *         then by message
     */
    fun execute(
        executionInput: ExecutionInput,
        schemaId: SchemaId = SchemaId.Full
    ): ExecutionResult

    /**
     * Returns the set of scope IDs applied to the given [schemaId], or `null` if no
     * scopes are configured for it.
     *
     * @param schemaId the schema whose applied scopes to retrieve
     * @return the set of applied scope IDs, or `null`
     */
    fun getAppliedScopes(schemaId: SchemaId): Set<String>?
}
