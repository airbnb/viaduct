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
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.SubqueryExecutionException
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.FieldSelectivityProvider
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
    fieldSelectivityProvider: FieldSelectivityProvider = FieldSelectivityProvider.Never,
) {
    // Constructing this is expensive, so do it just once per schema-version
    private val engineSelectionSetFactory: EngineSelectionSet.Factory = EngineSelectionSetFactoryImpl(fullSchema)
    private val fieldSelectivity: IsResolverSelective = IsResolverSelective(fieldSelectivityProvider::isSelective)

    fun create(
        scopedSchema: ViaductSchema,
        requestContext: Any?
    ): EngineExecutionContext {
        val matResolutionEnabled = flagManager.isEnabled(FlagManager.Flags.ENABLE_MAT_RESOLUTION)
        val isResolverSelective = fieldSelectivity

        return EngineExecutionContextImpl(
            fullSchema,
            scopedSchema,
            requestContext,
            engineSelectionSetFactory,
            dispatcherRegistry,
            resolverInstrumentation,
            ConcurrentHashMap<FieldDataLoaderKey, FieldDataLoader>(),
            ConcurrentHashMap<String, NodeDataLoader>(),
            selectiveOERKeysEnabled = false,
            flagManager.isEnabled(FlagManager.Flags.KILLSWITCH_FIELD_RSS_ORIGIN_FILTERING),
            matResolutionEnabled,
            engine,
            globalIDCodec,
            meterRegistry,
            isResolverSelective,
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
class EngineExecutionContextImpl internal constructor(
    override val fullSchema: ViaductSchema,
    override val scopedSchema: ViaductSchema,
    override val requestContext: Any?,
    override val engineSelectionSetFactory: EngineSelectionSet.Factory,
    val dispatcherRegistry: DispatcherRegistry,
    val resolverInstrumentation: Instrumentation,
    internal val fieldDataLoaders: ConcurrentHashMap<FieldDataLoaderKey, FieldDataLoader>,
    internal val nodeDataLoaders: ConcurrentHashMap<String, NodeDataLoader>,
    val selectiveOERKeysEnabled: Boolean,
    val fieldRssOriginFilteringKillSwitchEnabled: Boolean,
    val matResolutionEnabled: Boolean,
    override val engine: Engine,
    override val globalIDCodec: GlobalIDCodec,
    private val meterRegistry: MeterRegistry?,
    val isResolverSelective: IsResolverSelective,
    var dataFetchingEnvironment: DataFetchingEnvironment? = null,
    override val activeSchema: ViaductSchema = fullSchema,
    internal val fieldScopeSupplier: Supplier<out EngineExecutionContext.FieldExecutionScope> = FpKit.intraThreadMemoize { FieldExecutionScopeImpl() },
    executionHandle: EngineExecutionContext.ExecutionHandle? = null,
    internal val matBatchDepth: Int = 0,
) : InternalEngineExecutionContext {
    public override val impl: EngineExecutionContextImpl get() = this

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

    override fun createRootFieldReference(
        rootFieldPath: List<String>,
        type: GraphQLObjectType,
        args: Map<String, Any?>,
    ): RootFieldReference {
        return ObjectRootFieldReference(rootFieldPath, type, args)
    }

    override fun hasModernNodeResolver(typeName: String): Boolean {
        return dispatcherRegistry.getNodeResolverDispatcher(typeName) != null
    }

    override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync = resolveSelectionSet(selectionSet, options, instrumentationContext = null)

    /**
     * Subquery materialization with an optional [ResolverInstrumentationContext].
     *
     * When [instrumentationContext] is non-null and [engine] implements
     * [SubqueryInstrumentationEngine] (always true in production — `engine` is `EngineImpl`),
     * per-selection fetch instrumentation fires for the resolved fields. Otherwise this falls back
     * to the plain [Engine.resolveSelectionSet], so non-instrumented callers and test doubles are
     * unaffected.
     */
    internal suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
        instrumentationContext: ResolverInstrumentationContext?,
    ): EngineObjectData.Sync {
        val handle = executionHandle
            ?: throw SubqueryExecutionException(
                "resolveSelectionSet requires an executionHandle. " +
                    "This typically means resolveSelectionSet was called before execution started " +
                    "or from a context that doesn't have access to the current execution."
            )

        val effectiveOptions = options.copy(attribution = fieldScope.attribution)

        return executeWithMetrics {
            val subqueryInstrumentationEngine = engine as? SubqueryInstrumentationEngine
            if (instrumentationContext == null || subqueryInstrumentationEngine == null) {
                engine.resolveSelectionSet(handle, selectionSet, effectiveOptions)
            } else {
                subqueryInstrumentationEngine.resolveSelectionSet(
                    handle,
                    selectionSet,
                    effectiveOptions,
                    instrumentationContext,
                )
            }
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

    private inline fun <T : EngineObjectData> executeWithMetrics(block: () -> T): T {
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
        fieldDataLoaders.computeIfAbsent(FieldDataLoaderKey(resolver.resolverId, matBatchDepth)) {
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
        selectiveOERKeysEnabled: Boolean = this.selectiveOERKeysEnabled,
        fieldRssOriginFilteringKillSwitchEnabled: Boolean = this.fieldRssOriginFilteringKillSwitchEnabled,
        matResolutionEnabled: Boolean = this.matResolutionEnabled,
        matBatchDepth: Int? = null,
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
            selectiveOERKeysEnabled = selectiveOERKeysEnabled,
            fieldRssOriginFilteringKillSwitchEnabled = fieldRssOriginFilteringKillSwitchEnabled,
            matResolutionEnabled = matResolutionEnabled,
            engine = this.engine,
            globalIDCodec = this.globalIDCodec,
            meterRegistry = this.meterRegistry,
            isResolverSelective = this.isResolverSelective,
            dataFetchingEnvironment = dataFetchingEnvironment,
            fieldScopeSupplier = fieldScopeSupplier,
            executionHandle = this._executionHandle,
            matBatchDepth = matBatchDepth ?: this.matBatchDepth,
        )
    }
}

/**
 * Identifies the field loader for one resolver and Mat depth within a request.
 *
 * Including the depth keeps a Mat re-run in a separate batch from the call waiting for it.
 */
internal data class FieldDataLoaderKey(
    val resolverId: String,
    val matBatchDepth: Int,
)
