package viaduct.engine.api

import graphql.language.FragmentDefinition
import graphql.schema.GraphQLObjectType
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Request-scoped execution context used to pass contextual elements to tenant API implementations
 *
 * ## Contextual Scopes
 *
 * This context contains three types of state with different lifecycles:
 * - **View-scoped** (schema/code): Rarely changes, shared across requests
 * - **Request-scoped**: Once per GraphQL request
 * - **Field-scoped**: Changes during execution tree traversal (see [FieldExecutionScope])
 */
interface EngineExecutionContext {
    // View-scoped: Schema and execution infrastructure
    val fullSchema: ViaductSchema
    val scopedSchema: ViaductSchema
    val activeSchema: ViaductSchema
    val rawSelectionSetFactory: RawSelectionSet.Factory
    val rawSelectionsLoaderFactory: RawSelectionsLoader.Factory

    /**
     * The GlobalIDCodec shared across all tenant-API implementations in this Viaduct instance.
     * This ensures that GlobalIDs serialized by one tenant module can be correctly deserialized
     * by another tenant module.
     */
    val globalIDCodec: GlobalIDCodec

    // Request-scoped: Per-request context set by Viaduct Service Engineers
    val requestContext: Any?

    /**
     * The engine that is currently executing this request, enabling follow-up executions within the same lifecycle.
     */
    val engine: Engine

    /**
     * An opaque handle to the ongoing execution that enables subquery execution.
     *
     * This handle is set automatically by the engine when execution begins. It allows
     * the engine to associate this context with the correct execution state when
     * [executeSelectionSet] is called.
     *
     * ## Lifecycle
     *
     * - **Before execution**: `null` ([EngineExecutionContext] exists but execution hasn't started)
     * - **During execution**: Set to an opaque handle representing the current execution
     * - **Propagated on copy**: Derived [EngineExecutionContext]s maintain the handle to their owning execution
     *
     * Tenant runtime code should treat this as read-only; the setter is internal to the runtime module.
     *
     * @see ExecutionHandle
     */
    val executionHandle: ExecutionHandle?

    // Field-scoped: Changes during execution tree traversal
    /**
     * Field-level execution scope that changes as we traverse the execution tree.
     *
     * This contains execution state that is context-sensitive based on execution depth:
     * - During root operation execution: client query's fragments and variables
     * - During child plan execution (RSS): child plan's fragments and variables
     *
     * Access fragments/variables via:
     * - `fieldScope.fragments` instead of deprecated direct access
     * - `fieldScope.variables` instead of deprecated direct access
     */
    val fieldScope: FieldExecutionScope

    /**
     * Field-level execution scope that changes as we traverse the execution tree.
     *
     * This scope contains execution state that is context-sensitive based on execution depth.
     * It separates field-scoped state (which changes during tree traversal) from view-scoped
     * state (schema/code) and request-scoped state (per-request context).
     *
     * ## Context Sensitivity
     *
     * The properties in this scope vary based on where we are in the execution tree:
     * - **During root operation execution**: Contains the client query's fragments and variables
     * - **During child plan execution** (e.g., resolver RSS): Contains the
     *   child plan's fragments and variables
     *
     * This ensures that code always has the correct fragments and variables for its execution
     * context, whether resolving the root query or executing a child plan.
     *
     * ## Lifecycle
     *
     * Field scope is created per-field during execution and may be replaced as we traverse
     * into child plans. This is in contrast to:
     * - View scope: Rarely changes (only on schema/code updates)
     * - Request scope: Once per GraphQL request
     */
    interface FieldExecutionScope {
        /**
         * Fragments available in the current execution context.
         *
         * These are context-sensitive:
         * - Root execution: The client query's fragment definitions
         * - Child plan execution: The child plan's fragment definitions
         */
        val fragments: Map<String, FragmentDefinition>

        /**
         * Variables available in the current execution context.
         *
         * These are context-sensitive:
         * - Root execution: The client query's variables (coerced)
         * - Child plan execution: The resolved child plan variables
         */
        val variables: Map<String, Any?>

        /**
         * The policy governing how fields within this scope should be resolved.
         *
         * This is determined by the result of the parent field's execution.
         * - [ResolutionPolicy.STANDARD]: Normal execution (lookup resolvers).
         * - [ResolutionPolicy.PARENT_MANAGED]: Driven by [ParentManagedValue], skipping resolvers.
         */
        val resolutionPolicy: ResolutionPolicy
    }

    /**
     * Interface representing an opaque handle representing an ongoing execution.
     *
     * This handle enables subquery execution (via [executeSelectionSet]) without tenant runtime
     * code needing to understand execution internals. The engine uses this handle to:
     * - Access the current execution's coroutine scope and error accumulator
     * - Maintain parent-child relationships for error attribution
     * - Continue execution within the same request lifecycle
     *
     * @see executionHandle
     */
    interface ExecutionHandle

    /**
     * Executes a selection set against the engine with configurable options.
     *
     * This is the primary API for internal selection execution, providing control
     * over operation type and memoization behavior.
     *
     * ## Three-Tier Architecture
     *
     * This method is the Engine API layer in the three-tier architecture:
     * - **Tenant**: `ctx.query(SelectionSet<T>)` / `ctx.mutation(SelectionSet<T>)` - typed, simple
     * - **Engine API**: `EEC.executeSelectionSet(...)` - flexible, for shims and engine internals
     * - **Wiring**: `Engine.executeSelectionSet(...)` - implementation detail, only called by EEC
     *
     * ## Execution Handle Requirements
     *
     * If [ExecuteSelectionSetOptions.targetResult] is set, this method requires:
     * - a non-null [executionHandle], and
     * - the `ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE` flag to be enabled.
     *
     * If these conditions are not met, it will **fail fast** with [SubqueryExecutionException]
     * rather than silently degrading behavior.
     *
     * For basic execution (default options with [ExecuteSelectionSetOptions.targetResult] == null),
     * this method may fall back to the legacy [RawSelectionsLoader] path when the handle-based
     * path is unavailable.
     *
     * @param resolverId Identifier for instrumentation and tracing
     * @param selectionSet The [RawSelectionSet] containing the fields to resolve
     * @param options Execution options controlling behavior. Default executes as a Query.
     * @return The resolved [EngineObjectData]
     * @throws SubqueryExecutionException if [ExecuteSelectionSetOptions.targetResult] is requested
     *         but handle-based execution is not available, the schema doesn't support the
     *         requested operation type, or field resolution fails
     * @see ExecuteSelectionSetOptions For available options
     */
    suspend fun executeSelectionSet(
        resolverId: String,
        selectionSet: RawSelectionSet,
        options: ExecuteSelectionSetOptions = ExecuteSelectionSetOptions.DEFAULT,
    ): EngineObjectData

    fun createNodeReference(
        id: String,
        graphQLObjectType: GraphQLObjectType,
    ): NodeReference

    // TODO(https://app.asana.com/1/150975571430/project/1203659453427089/task/1210861903745772):
    //    remove when everything has been shimmed
    fun hasModernNodeResolver(typeName: String): Boolean
}
