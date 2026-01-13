package viaduct.service.api

import graphql.ExecutionResult
import graphql.schema.GraphQLSchema
import java.util.concurrent.CompletableFuture
import viaduct.apiannotations.StableApi

/**
 * A unified interface for configuring and executing queries against the Viaduct runtime
 */
@StableApi
interface Viaduct {
    /**
     *  Executes an operation on this Viaduct instance asynchronously.
     *
     *  @param executionInput The execution Input
     *  @param schemaId the id of the schema for which we want to execute the operation, defaults to SchemaId.Full
     *  @return the [CompletableFuture] of [ExecutionResult] who contains the sorted results or the error which was produced
     */
    suspend fun executeAsync(
        executionInput: ExecutionInput,
        schemaId: SchemaId = SchemaId.Full
    ): CompletableFuture<ExecutionResult>

    /**
     *  Executes an operation on this Viaduct instance.
     *
     *  @param executionInput the execution input for this operation
     *  @param schemaId the id of the schema for which we want to execute the operation, defaults to SchemaId.Full
     *  @return the ExecutionResult who contains the sorted results
     */
    fun execute(
        executionInput: ExecutionInput,
        schemaId: SchemaId = SchemaId.Full
    ): ExecutionResult

    /**
     * This function is used to get the applied scopes for a given schemaId
     *
     * @param schemaId the id of the schema for which we want a [GraphQLSchema]
     * @return Set of scopes that are applied to the schema
     */
    fun getAppliedScopes(schemaId: SchemaId): Set<String>?
}
