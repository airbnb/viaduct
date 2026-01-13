package viaduct.engine.runtime

import graphql.execution.instrumentation.Instrumentation
import graphql.language.FragmentDefinition
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import graphql.util.FpKit
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecuteSelectionSetOptions
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RawSelectionsLoader
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.api.SubqueryExecutionException
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flags
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.utils.slf4j.logger

/**
 * Factory for creating an engine-execution context.
 * Basically holds version-scoped state.
 */
class EngineExecutionContextFactory(
    private val fullSchema: ViaductSchema,
    private val dispatcherRegistry: DispatcherRegistry,
    fragmentLoader: FragmentLoader,
    private val resolverInstrumentation: Instrumentation,
    private val flagManager: FlagManager,
    private val engine: Engine,
    private val globalIDCodec: GlobalIDCodec,
    private val meterRegistry: MeterRegistry?,
) {
    // Constructing this is expensive, so do it just once per schema-version
    private val rawSelectionSetFactory: RawSelectionSet.Factory = RawSelectionSetFactoryImpl(fullSchema)

    // Not expensive, but why not do it once anyway
    private val rawSelectionsLoaderFactory: RawSelectionsLoader.Factory =
        RawSelectionsLoaderImpl.Factory(fragmentLoader, fullSchema)

    fun create(
        scopedSchema: ViaductSchema,
        requestContext: Any?
    ): EngineExecutionContext {
        return EngineExecutionContextImpl(
            fullSchema,
            scopedSchema,
            requestContext,
            rawSelectionSetFactory,
            rawSelectionsLoaderFactory,
            dispatcherRegistry,
            resolverInstrumentation,
            ConcurrentHashMap<String, FieldDataLoader>(),
            ConcurrentHashMap<String, NodeDataLoader>(),
            flagManager.isEnabled(Flags.EXECUTE_ACCESS_CHECKS),
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
    override val rawSelectionSetFactory: RawSelectionSet.Factory,
    override val rawSelectionsLoaderFactory: RawSelectionsLoader.Factory,
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
        private val log by logger()

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
    ) : EngineExecutionContext.FieldExecutionScope

    override fun createNodeReference(
        id: String,
        graphQLObjectType: GraphQLObjectType
    ) = NodeEngineObjectDataImpl(id, graphQLObjectType, dispatcherRegistry)

    override fun hasModernNodeResolver(typeName: String): Boolean {
        return dispatcherRegistry.getNodeResolverDispatcher(typeName) != null
    }

    override suspend fun executeSelectionSet(
        resolverId: String,
        selectionSet: RawSelectionSet,
        options: ExecuteSelectionSetOptions,
    ): EngineObjectData {
        val handle = executionHandle
        val useHandlePath = flagManager.isEnabled(Flags.ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE) && handle != null

        if (useHandlePath) {
            return executeWithMetrics("handle") {
                engine.executeSelectionSet(handle!!, selectionSet, options)
            }
        }

        // Legacy fallback doesn't support targetResult
        if (options.targetResult != null) {
            incrementSubqueryExecutionCounter("legacy", success = false)
            throw SubqueryExecutionException(
                "targetResult option requires executionHandle and ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE flag"
            )
        }

        log.debug(
            "Falling back to legacy rawSelectionsLoader path for resolverId={}",
            resolverId
        )

        return executeWithMetrics("legacy") {
            when (options.operationType) {
                Engine.OperationType.QUERY -> rawSelectionsLoaderFactory.forQuery(resolverId).load(selectionSet)
                Engine.OperationType.MUTATION -> rawSelectionsLoaderFactory.forMutation(resolverId).load(selectionSet)
            }
        }
    }

    private suspend inline fun executeWithMetrics(
        path: String,
        block: () -> EngineObjectData
    ): EngineObjectData {
        return try {
            block().also { incrementSubqueryExecutionCounter(path, success = true) }
        } catch (e: Exception) {
            incrementSubqueryExecutionCounter(path, success = false)
            throw e
        }
    }

    private fun incrementSubqueryExecutionCounter(
        path: String,
        success: Boolean
    ) {
        meterRegistry?.counter(
            SUBQUERY_EXECUTION_METER_NAME,
            "path",
            path,
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
            rawSelectionSetFactory = this.rawSelectionSetFactory,
            rawSelectionsLoaderFactory = this.rawSelectionsLoaderFactory,
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
