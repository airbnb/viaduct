package viaduct.tenant.runtime.bootstrap

import graphql.language.FragmentDefinition
import viaduct.api.NodeResolverBase
import viaduct.api.ResolverBase
import viaduct.api.internal.BaseBatchedFieldResolver
import viaduct.api.internal.BaseBatchedNodeResolver
import viaduct.api.internal.BaseUnbatchedFieldResolver
import viaduct.api.internal.BaseUnbatchedNodeResolver
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.GRT_PACKAGE_PREFIX
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.bootstrap.ExecutionRegistryConfigFile
import viaduct.bootstrap.FieldEntryConfig
import viaduct.bootstrap.NodeEntryConfig
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.TenantModuleMetadata
import viaduct.engine.api.bootstrap.executionregistry.RequiredSelectionSetSupport
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.service.api.spi.CodeInjector
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.execution.FieldBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.FieldUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.execution.VariablesProviderExecutor
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl
import viaduct.tenant.runtime.internal.VariablesProviderInfo
import viaduct.utils.slf4j.logger

class ViaductModernExecutorFactory(
    private val codeInjector: CodeInjector,
    private val grtPackagePrefix: String,
    private val registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    constructor(codeInjector: CodeInjector, registry: ExecutionRegistryConfigFile) :
        this(codeInjector, GRT_PACKAGE_PREFIX, registry)

    private val grtConvFactory = DefaultGRTConvFactory
    private val reflectionLoader = ReflectionLoaderImpl { name ->
        @Suppress("UNCHECKED_CAST")
        Class.forName("$grtPackagePrefix.$name").kotlin
    }

    private val requiredSelectionSetFactory = RequiredSelectionSetFactory

    private fun tenantMetadataFor(
        resolverClass: Class<*>,
        tenantAPIData: Map<String, Any?>,
    ): TenantModuleMetadata? {
        val metadata = tenantAPIData["tenantMetadata"]
        require(metadata is Map<*, *>) {
            "Missing or invalid generated tenantMetadata for ${resolverClass.name}; regenerate the tenant module config"
        }
        if (metadata.isEmpty()) return null
        val name = metadata["name"]
        require(name is String && name.isNotBlank()) { "Invalid generated tenantMetadata name for ${resolverClass.name}" }
        return TenantModuleMetadata(name = name)
    }

    private val namedFragments: Map<String, FragmentDefinition> by lazy {
        registry.namedFragments
            .flatMap { CachedDocumentParser.parseDocument(it).getDefinitionsOfType(FragmentDefinition::class.java) }
            .associateBy { it.name }
    }

    @Suppress("UNCHECKED_CAST")
    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: EngineSchema
    ): FieldResolverExecutor {
        val apiData = configData.tenantAPIData.toFieldAPIData()
        val resolverClass = loadClass<ResolverBase<*>>(apiData.resolverClass, "field ${configData.typeName}.${configData.fieldName}")

        val provider = codeInjector.getProvider(resolverClass)
        val attribution = ExecutionAttribution.fromResolver(apiData.resolverClass)

        val contextFactory = FieldExecutionContextFactory.of(
            resolverClass = resolverClass,
            reflectionLoader = reflectionLoader,
            typeName = configData.typeName,
            fieldName = configData.fieldName,
            hasArguments = apiData.hasArguments,
            queryTypeName = apiData.queryTypeName,
            returnTypeName = apiData.returnTypeName,
            grtConvFactory = grtConvFactory,
            knownFragments = namedFragments,
        )

        val selectionVariables = RequiredSelectionSetSupport.buildSelectionSetVariables(
            configData.objectSelections,
            configData.querySelections,
        )
        val hasSelectionConfiguration =
            configData.objectSelections != null || configData.querySelections != null

        val variablesProviderInfo = if (hasSelectionConfiguration) {
            resolverClass.kotlin.variablesProvider(codeInjector)
        } else {
            null
        }
        val (objectSelectionSet, querySelectionSet) = buildSelectionSets(
            entry = configData,
            variablesProviderInfo = variablesProviderInfo,
            variables = selectionVariables,
            attribution = attribution,
            contextFactory = contextFactory,
            queryTypeName = apiData.queryTypeName,
        )
        val argumentVariables = VariableFromArgumentDefinitions(
            selectionVariables.filterIsInstance<FromArgumentVariable>().associate { it.name to it.valueFromPath }
        )
        val objectFieldVariables = VariableFromFieldDefinitions(
            selectionVariables.filterIsInstance<FromObjectFieldVariable>().associate { it.name to it.valueFromPath }
        )
        val queryFieldVariables = VariableFromFieldDefinitions(
            selectionVariables.filterIsInstance<FromQueryFieldVariable>().associate { it.name to it.valueFromPath }
        )
        val variablesFromFunctionProvider = variablesProviderInfo?.let { VariablesProviderExecutor(it, contextFactory) }
        val resolverId = "${configData.typeName}.${configData.fieldName}"
        val tenantMetadata = tenantMetadataFor(resolverClass, configData.tenantAPIData)

        return if (configData.isBatching) {
            requireBaseResolver(resolverClass, BaseBatchedFieldResolver::class.java, "Batch field resolver")
            log.info("- Adding batch field resolver for '{}.{}'", configData.typeName, configData.fieldName)
            FieldBatchResolverExecutorImpl(
                objectSelectionSet = objectSelectionSet,
                querySelectionSet = querySelectionSet,
                isSelective = configData.isSelective,
                resolver = provider as javax.inject.Provider<BaseBatchedFieldResolver>,
                resolverId = resolverId,
                resolverContextFactory = contextFactory,
                resolverName = apiData.resolverClass,
                argumentVariables = argumentVariables,
                objectFieldVariables = objectFieldVariables,
                queryFieldVariables = queryFieldVariables,
                variablesFromFunctionProvider = variablesFromFunctionProvider,
                tenantMetadata = tenantMetadata,
            )
        } else {
            requireBaseResolver(resolverClass, BaseUnbatchedFieldResolver::class.java, "Field resolver")
            log.info("- Adding field resolver for '{}.{}'", configData.typeName, configData.fieldName)
            FieldUnbatchedResolverExecutorImpl(
                objectSelectionSet = objectSelectionSet,
                querySelectionSet = querySelectionSet,
                isSelective = configData.isSelective,
                resolver = provider as javax.inject.Provider<BaseUnbatchedFieldResolver>,
                resolverId = resolverId,
                resolverContextFactory = contextFactory,
                resolverName = apiData.resolverClass,
                argumentVariables = argumentVariables,
                objectFieldVariables = objectFieldVariables,
                queryFieldVariables = queryFieldVariables,
                variablesFromFunctionProvider = variablesFromFunctionProvider,
                tenantMetadata = tenantMetadata,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: EngineSchema
    ): NodeResolverExecutor {
        val apiData = configData.tenantAPIData.toNodeAPIData()
        val resolverClass = loadClass<NodeResolverBase<*>>(apiData.resolverClass, "node ${configData.typeName}")

        val provider = codeInjector.getProvider(resolverClass)

        val reflectiveType = reflectionLoader.reflectionFor(configData.typeName) as Type<NodeObject>
        val contextFactory = NodeExecutionContextFactory(
            reflectionLoader = reflectionLoader,
            resultType = reflectiveType,
            grtConvFactory = grtConvFactory,
            knownFragments = namedFragments,
        )

        val tenantMetadata = tenantMetadataFor(resolverClass, configData.tenantAPIData)

        return if (configData.isBatching) {
            requireBaseResolver(resolverClass, BaseBatchedNodeResolver::class.java, "Batch node resolver")
            log.info("- Adding batch node resolver for '{}'", configData.typeName)
            NodeBatchResolverExecutorImpl(
                resolver = provider as javax.inject.Provider<BaseBatchedNodeResolver>,
                typeName = configData.typeName,
                factory = contextFactory,
                resolverName = apiData.resolverClass,
                isSelective = configData.isSelective,
                tenantMetadata = tenantMetadata,
            )
        } else {
            requireBaseResolver(resolverClass, BaseUnbatchedNodeResolver::class.java, "Node resolver")
            log.info("- Adding node resolver for '{}'", configData.typeName)
            NodeUnbatchedResolverExecutorImpl(
                resolver = provider as javax.inject.Provider<BaseUnbatchedNodeResolver>,
                typeName = configData.typeName,
                factory = contextFactory,
                resolverName = apiData.resolverClass,
                isSelective = configData.isSelective,
                tenantMetadata = tenantMetadata,
            )
        }
    }

    private fun buildSelectionSets(
        entry: FieldEntryConfig,
        variablesProviderInfo: VariablesProviderInfo?,
        variables: List<SelectionSetVariable>,
        attribution: ExecutionAttribution,
        contextFactory: FieldExecutionContextFactory,
        queryTypeName: String,
    ): Pair<RequiredSelectionSet?, RequiredSelectionSet?> {
        val objectSelections = entry.objectSelections?.let {
            SelectionsParser.parse(entry.typeName, it.selections)
        }
        val querySelections = entry.querySelections?.let {
            SelectionsParser.parse(queryTypeName, it.selections)
        }

        if (objectSelections == null && querySelections == null) return Pair(null, null)

        return requiredSelectionSetFactory.createRequiredSelectionSets(
            variablesProvider = variablesProviderInfo,
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = contextFactory,
            variables = variables,
            attribution = attribution,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadClass(
        fqn: String,
        context: String,
    ): Class<out T> {
        try {
            return Class.forName(fqn) as Class<out T>
        } catch (e: ClassNotFoundException) {
            throw ClassNotFoundException("Cannot load class '$fqn' for $context", e)
        }
    }

    private fun requireBaseResolver(
        resolverClass: Class<*>,
        baseResolverClass: Class<*>,
        resolverDescription: String,
    ) {
        check(baseResolverClass.isAssignableFrom(resolverClass)) {
            "$resolverDescription ${resolverClass.name} does not implement ${baseResolverClass.simpleName}; " +
                "its generated resolver base is out of date or incompatible with this runtime"
        }
    }

    companion object {
        private val log by logger()
    }
}
