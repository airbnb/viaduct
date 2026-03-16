package viaduct.engine.runtime

import graphql.execution.instrumentation.Instrumentation
import graphql.language.FragmentDefinition
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import graphql.util.FpKit
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import viaduct.engine.api.CompleteSelectionSetOptions
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.SubqueryExecutionException
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Factory for creating an engine-execution context.
 * Basically holds version-scoped state.
 */
class EngineExecutionContextFactory(
    private val fullSchema: ViaductSchema,
    private val dispatcherRegistry: DispatcherRegistry,
    private val resolverInstrumentation: Instrumentation,
    private val flagManager: FlagManager,
    private val engine: Engine,
    private val globalIDCodec: GlobalIDCodec,
    private val meterRegistry: MeterRegistry?,
) {
    // Constructing this is expensive, so do it just once per schema-version
    private val engineSelectionSetFactory: EngineSelectionSet.Factory = EngineSelectionSetFactoryImpl(fullSchema)

    fun create(
        scopedSchema: ViaductSchema,
        requestContext: Any?
    ): EngineExecutionContext {
        return EngineExecutionContextImpl(
            fullSchema,
            scopedSchema,
            requestContext,
            engineSelectionSetFactory,
            dispatcherRegistry,
            resolverInstrumentation,
            ConcurrentHashMap<String, FieldDataLoader>(),
            ConcurrentHashMap<String, NodeDataLoader>(),
            flagManager.isEnabled(FlagManager.Flags.EXECUTE_ACCESS_CHECKS),
            engine,
            globalIDCodec,
            flagManager,
            meterRegistry,
        )
    }
}

/**
 * Runtime implementation of [EngineExecutionContext].
 *
 * This class holds all execution state and is copied as we traverse the execution tree.
 * Each copy maintains references to shared request-scoped state (like [fieldDataLoaders])
 * while allowing field-scoped state (like [fieldScopeSupplier]) to vary.
 *
 * ## Copying
 *
 * Use [EngineExecutionContextExtensions.copy] to create copies with modified field scope or DFE.
 * Copies automatically preserve the [executionHandle], so there is no need to manually set it.
 *
 * ## Execution Handle
 *
 * The [_executionHandle] backing field is mutable internally but exposed as read-only
 * via the [executionHandle] property. The handle is set eagerly when an
 * [viaduct.engine.runtime.execution.ExecutionParameters] is created, ensuring that any subsequent
 * copies preserve the correct handle.
 *
 * @see EngineExecutionContextFactory for creation
 * @see EngineExecutionContextExtensions for extension functions
 */
class EngineExecutionContextImpl(
    override val fullSchema: ViaductSchema,
    override val scopedSchema: ViaductSchema,
    override val requestContext: Any?,
    override val engineSelectionSetFactory: EngineSelectionSet.Factory,
    val dispatcherRegistry: DispatcherRegistry,
    val resolverInstrumentation: Instrumentation,
    internal val fieldDataLoaders: ConcurrentHashMap<String, FieldDataLoader>,
    internal val nodeDataLoaders: ConcurrentHashMap<String, NodeDataLoader>,
    val executeAccessChecksInModstrat: Boolean,
    override val engine: Engine,
    override val globalIDCodec: GlobalIDCodec,
    private val flagManager: FlagManager,
    private val meterRegistry: MeterRegistry?,
    var dataFetchingEnvironment: DataFetchingEnvironment? = null,
    override val activeSchema: ViaductSchema = fullSchema,
    internal val fieldScopeSupplier: Supplier<out EngineExecutionContext.FieldExecutionScope> = FpKit.intraThreadMemoize { FieldExecutionScopeImpl() },
    executionHandle: EngineExecutionContext.ExecutionHandle? = null,
) : EngineExecutionContext {
    companion object {
        const val SUBQUERY_EXECUTION_METER_NAME = "viaduct.subquery.execution"
    }

    // Backing field for executionHandle - mutable internally, but exposed as val on interface
    @Suppress("PropertyName")
    internal var _executionHandle: EngineExecutionContext.ExecutionHandle? = executionHandle
    override val executionHandle: EngineExecutionContext.ExecutionHandle?
        get() = _executionHandle

    override val fieldScope: EngineExecutionContext.FieldExecutionScope by lazy { fieldScopeSupplier.get() }

    /**
     * Implementation of [EngineExecutionContext.FieldExecutionScope] that holds field-scoped
     * execution state.
     *
     * This is an immutable data class that gets replaced as we traverse into child plans during execution.
     */
    data class FieldExecutionScopeImpl(
        override val fragments: Map<String, FragmentDefinition> = emptyMap(),
        override val variables: Map<String, Any?> = emptyMap(),
        override val resolutionPolicy: ResolutionPolicy = ResolutionPolicy.STANDARD,
        override val attribution: ExecutionAttribution = ExecutionAttribution.DEFAULT,
    ) : EngineExecutionContext.FieldExecutionScope

    override fun createNodeReference(
        id: String,
        graphQLObjectType: GraphQLObjectType
    ) = NodeEngineObjectDataImpl(id, graphQLObjectType, dispatcherRegistry)

    override fun hasModernNodeResolver(typeName: String): Boolean {
        return dispatcherRegistry.getNodeResolverDispatcher(typeName) != null
    }

    override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData {
        val handle = executionHandle
            ?: throw SubqueryExecutionException(
                "resolveSelectionSet requires an executionHandle. " +
                    "This typically means resolveSelectionSet was called before execution started " +
                    "or from a context that doesn't have access to the current execution."
            )

        return executeWithMetrics {
            engine.resolveSelectionSet(handle, selectionSet, options)
        }
    }

    override suspend fun completeSelectionSet(
        selectionSet: RequiredSelectionSet,
        arguments: Map<String, Any?>,
        options: CompleteSelectionSetOptions,
    ): graphql.ExecutionResult {
        val handle = executionHandle
            ?: throw SubqueryExecutionException(
                "completeSelectionSet requires an executionHandle. " +
                    "This typically means completeSelectionSet was called before execution started " +
                    "or from a context that doesn't have access to the current execution."
            )
        return engine.completeSelectionSet(handle, selectionSet, null, arguments, options)
    }

    override suspend fun completeSelectionSet(
        selectionSet: RequiredSelectionSet,
        targetResult: ObjectEngineResult,
        arguments: Map<String, Any?>,
        options: CompleteSelectionSetOptions,
    ): graphql.ExecutionResult {
        val handle = executionHandle
            ?: throw SubqueryExecutionException(
                "completeSelectionSet requires an executionHandle. " +
                    "This typically means completeSelectionSet was called before execution started " +
                    "or from a context that doesn't have access to the current execution."
            )
        return engine.completeSelectionSet(handle, selectionSet, targetResult, arguments, options)
    }

    private suspend inline fun executeWithMetrics(block: () -> EngineObjectData): EngineObjectData {
        return try {
            block().also { incrementSubqueryExecutionCounter(success = true) }
        } catch (e: Exception) {
            incrementSubqueryExecutionCounter(success = false)
            throw e
        }
    }

    private fun incrementSubqueryExecutionCounter(success: Boolean) {
        meterRegistry?.counter(
            SUBQUERY_EXECUTION_METER_NAME,
            "success",
            success.toString()
        )?.increment()
    }

    /**
     * Gets the [FieldDataLoader] for the given field coordinate if it already exists, otherwise
     * creates and returns a new one. The loader is request-scoped since it has the same
     * lifecycle as the [EngineExecutionContext].
     */
    internal fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader =
        fieldDataLoaders.computeIfAbsent(resolver.resolverId) {
            FieldDataLoader(resolver)
        }

    /**
     * Gets the [NodeDataLoader] for the given Node type if it already exists, otherwise
     * creates and returns a new one. The loader is request-scoped since it has the same
     * lifecycle as the [EngineExecutionContext].
     */
    internal fun nodeDataLoader(resolver: NodeResolverExecutor): NodeDataLoader =
        nodeDataLoaders.computeIfAbsent(resolver.typeName) {
            NodeDataLoader(resolver)
        }

    /**
     * Returns true iff field coordinate has a tenant-defined resolver function.
     */
    fun hasResolver(
        typeName: String,
        fieldName: String
    ): Boolean {
        return dispatcherRegistry.getFieldResolverDispatcher(typeName, fieldName) != null
    }

    /**
     * Internal copy with full control over all parameters.
     * This is the single source of truth for copying.
     *
     * **Do not call directly** - use [EngineExecutionContextExtensions.copy] extension instead.
     * This method is internal only because the extension needs access; it should be treated as private.
     */
    internal fun copy(
        activeSchema: ViaductSchema = this.activeSchema,
        fieldScopeSupplier: Supplier<out EngineExecutionContext.FieldExecutionScope> = this.fieldScopeSupplier,
        dataFetchingEnvironment: DataFetchingEnvironment? = this.dataFetchingEnvironment,
        executeAccessChecksInModstrat: Boolean = this.executeAccessChecksInModstrat,
    ): EngineExecutionContextImpl {
        return EngineExecutionContextImpl(
            fullSchema = this.fullSchema,
            scopedSchema = this.scopedSchema,
            requestContext = this.requestContext,
            activeSchema = activeSchema,
            engineSelectionSetFactory = this.engineSelectionSetFactory,
            dispatcherRegistry = this.dispatcherRegistry,
            resolverInstrumentation = this.resolverInstrumentation,
            fieldDataLoaders = this.fieldDataLoaders,
            nodeDataLoaders = this.nodeDataLoaders,
            executeAccessChecksInModstrat = executeAccessChecksInModstrat,
            engine = this.engine,
            globalIDCodec = this.globalIDCodec,
            flagManager = this.flagManager,
            meterRegistry = this.meterRegistry,
            dataFetchingEnvironment = dataFetchingEnvironment,
            fieldScopeSupplier = fieldScopeSupplier,
            executionHandle = this._executionHandle,
        )
    }
}
