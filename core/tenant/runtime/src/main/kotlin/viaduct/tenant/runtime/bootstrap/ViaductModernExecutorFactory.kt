package viaduct.tenant.runtime.bootstrap

import kotlin.reflect.full.declaredMemberFunctions
import viaduct.api.NodeResolverBase
import viaduct.api.ResolverBase
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.GRTConvFactory
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlock
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.TenantCodeInjector
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.execution.FieldBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.FieldUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl
import viaduct.utils.slf4j.logger

class ViaductModernExecutorFactory(
    private val tenantCodeInjector: TenantCodeInjector,
    private val grtPackagePrefix: String,
    private val grtConvFactory: GRTConvFactory = DefaultGRTConvFactory,
) : ExecutorFactory {
    private val reflectionLoader = ReflectionLoaderImpl { name ->
        @Suppress("UNCHECKED_CAST")
        Class.forName("$grtPackagePrefix.$name").kotlin
    }

    private val requiredSelectionSetFactory = RequiredSelectionSetFactory(reflectionLoader)

    @Suppress("UNCHECKED_CAST")
    override fun createFieldResolverExecutor(configData: FieldEntry): FieldResolverExecutor {
        val resolverClass = loadClass<ResolverBase<*>>(configData.tenantAPIData.resolverClass, "field ${configData.typeName}.${configData.fieldName}")
        val resolverBaseClass = loadClass<ResolverBase<*>>(configData.tenantAPIData.resolverBaseClass, "field resolver base for ${configData.typeName}.${configData.fieldName}")

        val provider = tenantCodeInjector.getProvider(resolverClass)
        val attribution = ExecutionAttribution.fromResolver(configData.tenantAPIData.resolverClass)

        val contextFactory = FieldExecutionContextFactory.of(
            resolverBaseClass = resolverBaseClass,
            reflectionLoader = reflectionLoader,
            typeName = configData.typeName,
            fieldName = configData.fieldName,
            hasArguments = configData.tenantAPIData.hasArguments,
            queryTypeName = configData.tenantAPIData.queryTypeName,
            returnTypeName = configData.tenantAPIData.returnTypeName,
            grtConvFactory = grtConvFactory,
        )

        val (objectSelectionSet, querySelectionSet) = buildSelectionSets(
            entry = configData,
            attribution = attribution,
            contextFactory = contextFactory,
        )

        val resolverKClass = resolverClass.kotlin
        val resolverId = "${configData.typeName}.${configData.fieldName}"

        return if (configData.isBatching) {
            val batchResolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { it.name == "batchResolve" }
                ?: error("Resolver ${configData.tenantAPIData.resolverClass} is marked isBatching=true but does not declare 'batchResolve'")
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
                resolverName = configData.tenantAPIData.resolverClass,
            )
        } else {
            val resolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { fn -> fn.name == "resolve" }
                ?: error("Resolver ${configData.tenantAPIData.resolverClass} does not declare 'resolve'")
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
                resolverName = configData.tenantAPIData.resolverClass,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun createNodeResolverExecutor(configData: NodeEntry): NodeResolverExecutor {
        val resolverClass = loadClass<NodeResolverBase<*>>(configData.tenantAPIData.resolverClass, "node ${configData.typeName}")
        val resolverBaseClass = loadClass<NodeResolverBase<*>>(configData.tenantAPIData.resolverBaseClass, "node resolver base for ${configData.typeName}")

        val provider = tenantCodeInjector.getProvider(resolverClass)

        val reflectiveType = reflectionLoader.reflectionFor(configData.typeName) as Type<NodeObject>
        val contextFactory = NodeExecutionContextFactory(
            resolverBaseClass = resolverBaseClass,
            reflectionLoader = reflectionLoader,
            resultType = reflectiveType,
            grtConvFactory = grtConvFactory,
        )

        val resolverKClass = resolverClass.kotlin

        return if (configData.isBatching) {
            val batchResolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { fn -> fn.name == "batchResolve" }
                ?: error("Resolver ${configData.tenantAPIData.resolverClass} is marked isBatching=true but does not declare 'batchResolve'")
            log.info("- Adding batch node resolver for '{}'", configData.typeName)
            NodeBatchResolverExecutorImpl(
                resolver = provider,
                batchResolveFunction = batchResolveFn,
                typeName = configData.typeName,
                reflectionLoader = reflectionLoader,
                factory = contextFactory,
                resolverName = configData.tenantAPIData.resolverClass,
                isSelective = configData.isSelective,
            )
        } else {
            val resolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { fn -> fn.name == "resolve" }
                ?: error("Resolver ${configData.tenantAPIData.resolverClass} does not declare 'resolve'")
            log.info("- Adding node resolver for '{}'", configData.typeName)
            NodeUnbatchedResolverExecutorImpl(
                resolver = provider,
                resolveFunction = resolveFn,
                typeName = configData.typeName,
                reflectionLoader = reflectionLoader,
                factory = contextFactory,
                resolverName = configData.tenantAPIData.resolverClass,
                isSelective = configData.isSelective,
            )
        }
    }

    private fun buildSelectionSets(
        entry: FieldEntry,
        attribution: ExecutionAttribution,
        contextFactory: FieldExecutionContextFactory,
    ): Pair<RequiredSelectionSet?, RequiredSelectionSet?> {
        val objectSelections = entry.objectSelections?.let {
            SelectionsParser.parse(entry.typeName, it.selections)
        }
        val querySelections = entry.querySelections?.let {
            SelectionsParser.parse(entry.tenantAPIData.queryTypeName, it.selections)
        }

        if (objectSelections == null && querySelections == null) return Pair(null, null)

        return requiredSelectionSetFactory.createRequiredSelectionSets(
            variablesProvider = null,
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = contextFactory,
            variables = buildVariables(entry.objectSelections, entry.querySelections),
            attribution = attribution,
        )
    }

    private fun buildVariables(
        objectSelections: SelectionsBlock?,
        querySelections: SelectionsBlock?,
    ): List<SelectionSetVariable> =
        (
            (objectSelections?.variablesProviders ?: emptyList()) +
                (querySelections?.variablesProviders ?: emptyList())
        )
            .flatMap { providerEntry ->
                providerEntry.providedVariables.keys.map { varName ->
                    providerEntry.providerVariablesAPIData.toSelectionSetVariable(varName)
                }
            }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadClass(
        fqn: String,
        context: String,
    ): Class<out T> {
        // KSP emits '.' as separator for nested classes; Class.forName requires '$'.
        // Try progressively moving the '.' → '$' boundary from the right until one resolves.
        // TODO KSP output should be fixed as this is not a robust way of solving this problem.
        // https://app.asana.com/1/150975571430/project/1207604899751448/task/1214403842090320?focus=true
        val parts = fqn.split('.')
        var lastCause: ClassNotFoundException? = null
        for (splitAt in parts.indices.reversed()) {
            val candidate = parts.take(splitAt + 1).joinToString(".") +
                if (splitAt < parts.lastIndex) "$" + parts.drop(splitAt + 1).joinToString("$") else ""
            try {
                return Class.forName(candidate) as Class<out T>
            } catch (e: ClassNotFoundException) {
                lastCause = e
            }
        }
        throw ClassNotFoundException("Cannot load class '$fqn' for $context", lastCause)
    }

    companion object {
        private val log by logger()
    }
}

private fun ProviderVariablesAPIData.toSelectionSetVariable(varName: String): SelectionSetVariable =
    when (type) {
        "fromArgument" -> FromArgumentVariable(varName, path)
        "fromObjectField" -> FromObjectFieldVariable(varName, path)
        "fromQueryField" -> FromQueryFieldVariable(varName, path)
        else -> error("Unknown variable provider type '$type' for variable '$varName'")
    }
