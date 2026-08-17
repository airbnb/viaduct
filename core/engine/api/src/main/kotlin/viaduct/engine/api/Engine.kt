package viaduct.engine.api

import graphql.ExecutionResult

/**
 * Core GraphQL execution engine that processes queries, mutations, and subscriptions
 * against a compiled Viaduct schema.
 */
interface Engine {
    val schema: ViaductSchema

    /**
     * Executes a GraphQL operation.
     *
     * @param executionInput The GraphQL operation to execute, including query text and variables
     * @return The completed GraphQL execution result containing data and errors
     */
    suspend fun execute(executionInput: ExecutionInput): ExecutionResult

    /**
     * Executes a selection set from within a resolver using an existing execution context,
     * returning a synchronous [EngineObjectData.Sync] with all fields eagerly resolved.
     *
     * @param executionHandle The opaque handle from the current execution context.
     * @param selectionSet The [EngineSelectionSet] containing the fields to resolve.
     * @param options The [ResolveSelectionSetOptions] controlling execution behavior.
     * @return The resolved [EngineObjectData.Sync] wrapping the target result.
     * @throws SubqueryExecutionException on execution failures.
     */
    suspend fun resolveSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync

    /**
     * Resolves the object-valued field at [rootFieldPath] without traversing its nested selections.
     *
     * @param executionHandle The opaque handle from the current execution context.
     * @param rootFieldPath The path to the root object field, e.g. ["fooFactory", "create"]
     * @param arguments Field arguments
     * @param selectionSet The [EngineSelectionSet] to pass to the field resolver being executed, if it's
     *        selective. This will not actually resolve the nested selections.
     * @param options The [ResolveRootFieldReferenceOptions] controlling execution behavior.
     * @return The original [EngineObjectData] returned by the referenced field resolver, or null
     *         if the field resolves to null.
     */
    suspend fun resolveRootFieldReference(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        rootFieldPath: List<String>,
        arguments: Map<String, Any?>,
        selectionSet: EngineSelectionSet,
        options: ResolveRootFieldReferenceOptions,
    ): EngineObjectData?

    /**
     * The type of operation for selection execution.
     */
    enum class OperationType {
        QUERY,
        MUTATION
    }
}
