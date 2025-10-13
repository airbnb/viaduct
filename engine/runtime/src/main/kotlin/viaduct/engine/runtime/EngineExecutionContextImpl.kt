package viaduct.engine.runtime

import graphql.execution.instrumentation.Instrumentation
import graphql.language.FragmentDefinition
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RawSelectionsLoader
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.Flags

/**
 * Factory for creating an engine-execution context.
 * Basically holds version-scoped state.
 *
 * TODO: This is the kind of class we'll want to put in the `viaduct.engine.bindings`
 * package, which is why it's not nested.
 *
 * TODO: At some point this will construct the DispatcherRegistry
 * from modules.
 */
class EngineExecutionContextFactory(
    private val fullSchema: ViaductSchema,
    private val dispatcherRegistry: DispatcherRegistry,
    fragmentLoader: FragmentLoader,
    private val resolverInstrumentation: Instrumentation,
    private val flagManager: FlagManager,
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
            flagManager.isEnabled(Flags.EXECUTE_ACCESS_CHECKS_IN_MODERN_EXECUTION_STRATEGY),
        )
    }
}

class EngineExecutionContextImpl(
    override val fullSchema: ViaductSchema,
    override val scopedSchema: ViaductSchema,
    override val requestContext: Any?,
    override val rawSelectionSetFactory: RawSelectionSet.Factory,
    override val rawSelectionsLoaderFactory: RawSelectionsLoader.Factory,
    val dispatcherRegistry: DispatcherRegistry,
    val resolverInstrumentation: Instrumentation,
    private val fieldDataLoaders: ConcurrentHashMap<String, FieldDataLoader>,
    private val nodeDataLoaders: ConcurrentHashMap<String, NodeDataLoader>,
    val executeAccessChecksInModstrat: Boolean,
    val dataFetchingEnvironment: DataFetchingEnvironment? = null,
    override val activeSchema: ViaductSchema = fullSchema,
    override val fieldScope: EngineExecutionContext.FieldExecutionScope = FieldExecutionScopeImpl(),
) : EngineExecutionContext {
    /**
     * Implementation of [EngineExecutionContext.FieldExecutionScope] that holds field-scoped
     * execution state.
     *
     * This is an immutable data class that gets replaced as we traverse into child plans during execution.
     */
    data class FieldExecutionScopeImpl(
        override val fragments: Map<String, FragmentDefinition> = emptyMap(),
        override val variables: Map<String, Any?> = emptyMap(),
    ) : EngineExecutionContext.FieldExecutionScope

    override fun createNodeReference(
        id: String,
        graphQLObjectType: GraphQLObjectType
    ) = NodeEngineObjectDataImpl(id, graphQLObjectType, dispatcherRegistry, dispatcherRegistry)

    override fun hasModernNodeResolver(typeName: String): Boolean {
        return dispatcherRegistry.getNodeResolverDispatcher(typeName) != null
    }

    /**
     * Gets the [FieldDataLoader] for the given field coordinate if it already exists, otherwise
     * creates and returns a new one. The loader is request-scoped since it has the same
     * lifecycle as the [EngineExecutionContext].
     */
    internal fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader =
        fieldDataLoaders.computeIfAbsent(resolver.resolverId) {
            FieldDataLoader(resolver, this)
        }

    /**
     * Gets the [NodeDataLoader] for the given Node type if it already exists, otherwise
     * creates and returns a new one. The loader is request-scoped since it has the same
     * lifecycle as the [EngineExecutionContext].
     */
    internal fun nodeDataLoader(resolver: NodeResolverExecutor): NodeDataLoader =
        nodeDataLoaders.computeIfAbsent(resolver.typeName) {
            NodeDataLoader(resolver, this)
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
     * Creates a copy of the current execution context
     */
    fun copy(
        dataFetchingEnvironment: DataFetchingEnvironment? = this.dataFetchingEnvironment,
        executeAccessCheckInModstrat: Boolean = this.executeAccessChecksInModstrat,
        activeSchema: ViaductSchema = this.activeSchema,
        fieldScope: EngineExecutionContext.FieldExecutionScope = this.fieldScope,
    ) = EngineExecutionContextImpl(
        fullSchema = this.fullSchema,
        scopedSchema = this.scopedSchema,
        requestContext = this.requestContext,
        activeSchema = activeSchema,
        rawSelectionSetFactory = this.rawSelectionSetFactory,
        rawSelectionsLoaderFactory = rawSelectionsLoaderFactory,
        dispatcherRegistry = this.dispatcherRegistry,
        resolverInstrumentation = this.resolverInstrumentation,
        fieldDataLoaders = this.fieldDataLoaders,
        nodeDataLoaders = this.nodeDataLoaders,
        executeAccessChecksInModstrat = executeAccessCheckInModstrat,
        dataFetchingEnvironment = dataFetchingEnvironment,
        fieldScope = fieldScope,
    )
}
