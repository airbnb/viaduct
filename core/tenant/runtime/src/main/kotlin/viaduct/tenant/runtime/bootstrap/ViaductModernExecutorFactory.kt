package viaduct.tenant.runtime.bootstrap

import graphql.language.FragmentDefinition
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberFunctions
import viaduct.api.NodeResolverBase
import viaduct.api.ResolverBase
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.GRT_PACKAGE_PREFIX
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.RequiredSelectionSetSupport
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlockConfig
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.execution.FieldBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.FieldUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl
import viaduct.utils.slf4j.logger

class ViaductModernExecutorFactory(
    private val codeInjector: CodeInjector,
    private val grtPackagePrefix: String,
    private val registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    /** Production constructor — GRT package sourced from the compile-time constant. */
    constructor(codeInjector: CodeInjector, registry: ExecutionRegistryConfigFile) : this(codeInjector, GRT_PACKAGE_PREFIX, registry)

    private val grtConvFactory = DefaultGRTConvFactory
    private val reflectionLoader = ReflectionLoaderImpl { name ->
        @Suppress("UNCHECKED_CAST")
        Class.forName("$grtPackagePrefix.$name").kotlin
    }

    private val requiredSelectionSetFactory = RequiredSelectionSetFactory(reflectionLoader)

    private val namedFragments: Map<String, FragmentDefinition> by lazy {
        registry.namedFragments
            .flatMap { CachedDocumentParser.parseDocument(it).getDefinitionsOfType(FragmentDefinition::class.java) }
            .associateBy { it.name }
    }

    @Suppress("UNCHECKED_CAST")
    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema
    ): FieldResolverExecutor {
        val apiData = configData.tenantAPIData.toFieldAPIData()
        val resolverClass = loadClass<ResolverBase<*>>(apiData.resolverClass, "field ${configData.typeName}.${configData.fieldName}")
        val resolverBaseClass = loadClass<ResolverBase<*>>(apiData.resolverBaseClass, "field resolver base for ${configData.typeName}.${configData.fieldName}")

        val provider = codeInjector.getProvider(resolverClass)
        val attribution = ExecutionAttribution.fromResolver(apiData.resolverClass)

        val contextFactory = FieldExecutionContextFactory.of(
            resolverBaseClass = resolverBaseClass,
            reflectionLoader = reflectionLoader,
            typeName = configData.typeName,
            fieldName = configData.fieldName,
            hasArguments = apiData.hasArguments,
            queryTypeName = apiData.queryTypeName,
            returnTypeName = apiData.returnTypeName,
            grtConvFactory = grtConvFactory,
            knownFragments = namedFragments,
        )

        val resolverKClass = resolverClass.kotlin

        val (objectSelectionSet, querySelectionSet) = buildSelectionSets(
            entry = configData,
            resolverKClass = resolverKClass,
            attribution = attribution,
            contextFactory = contextFactory,
            queryTypeName = apiData.queryTypeName,
        )
        val resolverId = "${configData.typeName}.${configData.fieldName}"

        return if (configData.isBatching) {
            val batchResolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { it.name == "batchResolve" }
                ?: error("Resolver ${apiData.resolverClass} is marked isBatching=true but does not declare 'batchResolve'")
            log.info("- Adding batch field resolver for '{}.{}'", configData.typeName, configData.fieldName)
            FieldBatchResolverExecutorImpl(
                objectSelectionSet = objectSelectionSet,
                querySelectionSet = querySelectionSet,
                isSelective = configData.isSelective,
                resolver = provider,
                batchResolveFn = batchResolveFn,
                resolverId = resolverId,
                reflectionLoader = reflectionLoader,
                resolverContextFactory = contextFactory,
                resolverName = apiData.resolverClass,
            )
        } else {
            val resolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { fn -> fn.name == "resolve" }
                ?: error("Resolver ${apiData.resolverClass} does not declare 'resolve'")
            log.info("- Adding field resolver for '{}.{}'", configData.typeName, configData.fieldName)
            FieldUnbatchedResolverExecutorImpl(
                objectSelectionSet = objectSelectionSet,
                querySelectionSet = querySelectionSet,
                isSelective = configData.isSelective,
                resolver = provider,
                resolveFn = resolveFn,
                resolverId = resolverId,
                reflectionLoader = reflectionLoader,
                resolverContextFactory = contextFactory,
                resolverName = apiData.resolverClass,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema
    ): NodeResolverExecutor {
        val apiData = configData.tenantAPIData.toNodeAPIData()
        val resolverClass = loadClass<NodeResolverBase<*>>(apiData.resolverClass, "node ${configData.typeName}")
        val resolverBaseClass = loadClass<NodeResolverBase<*>>(apiData.resolverBaseClass, "node resolver base for ${configData.typeName}")

        val provider = codeInjector.getProvider(resolverClass)

        val reflectiveType = reflectionLoader.reflectionFor(configData.typeName) as Type<NodeObject>
        val contextFactory = NodeExecutionContextFactory(
            resolverBaseClass = resolverBaseClass,
            reflectionLoader = reflectionLoader,
            resultType = reflectiveType,
            grtConvFactory = grtConvFactory,
            knownFragments = namedFragments,
        )

        val resolverKClass = resolverClass.kotlin

        return if (configData.isBatching) {
            val batchResolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { fn -> fn.name == "batchResolve" }
                ?: error("Resolver ${apiData.resolverClass} is marked isBatching=true but does not declare 'batchResolve'")
            log.info("- Adding batch node resolver for '{}'", configData.typeName)
            NodeBatchResolverExecutorImpl(
                resolver = provider,
                batchResolveFunction = batchResolveFn,
                typeName = configData.typeName,
                reflectionLoader = reflectionLoader,
                factory = contextFactory,
                resolverName = apiData.resolverClass,
                isSelective = configData.isSelective,
            )
        } else {
            val resolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { fn -> fn.name == "resolve" }
                ?: error("Resolver ${apiData.resolverClass} does not declare 'resolve'")
            log.info("- Adding node resolver for '{}'", configData.typeName)
            NodeUnbatchedResolverExecutorImpl(
                resolver = provider,
                resolveFunction = resolveFn,
                typeName = configData.typeName,
                reflectionLoader = reflectionLoader,
                factory = contextFactory,
                resolverName = apiData.resolverClass,
                isSelective = configData.isSelective,
            )
        }
    }

    private fun buildSelectionSets(
        entry: FieldEntryConfig,
        resolverKClass: KClass<out ResolverBase<*>>,
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
            variablesProvider = resolverKClass.variablesProvider(codeInjector),
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = contextFactory,
            variables = buildVariables(entry.objectSelections, entry.querySelections),
            attribution = attribution,
        )
    }

    private fun buildVariables(
        objectSelections: SelectionsBlockConfig?,
        querySelections: SelectionsBlockConfig?,
    ): List<SelectionSetVariable> = RequiredSelectionSetSupport.buildSelectionSetVariables(objectSelections, querySelections)

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

    companion object {
        private val log by logger()
    }
}
