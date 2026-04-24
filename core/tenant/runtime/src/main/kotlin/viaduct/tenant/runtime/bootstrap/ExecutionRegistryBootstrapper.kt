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
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistry
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlock
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.spi.TenantCodeInjector
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.execution.FieldBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.FieldUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeBatchResolverExecutorImpl
import viaduct.tenant.runtime.execution.NodeUnbatchedResolverExecutorImpl
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl
import viaduct.utils.slf4j.logger

/**
 * Builds executors from a pre-generated [ExecutionRegistry]
 *
 * The [schema] parameter passed to [fieldResolverExecutors] and [nodeResolverExecutors] is
 * intentionally ignored. Executor construction is driven entirely by the registry data,
 * which breaks the schema-executor coupling that exists in [ViaductTenantModuleBootstrapper].
 * The [schema] parameter cannot be removed because it is required by the [TenantModuleBootstrapper]
 * interface contract — changing that interface would break all existing implementations.
 */
class ExecutionRegistryBootstrapper(
    private val registry: ExecutionRegistry,
    private val tenantCodeInjector: TenantCodeInjector,
    private val grtPackagePrefix: String,
    private val grtConvFactory: GRTConvFactory = DefaultGRTConvFactory,
) : TenantModuleBootstrapper {
    private val reflectionLoader = ReflectionLoaderImpl { name ->
        @Suppress("UNCHECKED_CAST")
        Class.forName("$grtPackagePrefix.$name").kotlin
    }

    private val requiredSelectionSetFactory = RequiredSelectionSetFactory(reflectionLoader)

    // schema intentionally ignored — see class doc
    @Suppress("UNCHECKED_CAST")
    override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Pair<String, String>, FieldResolverExecutor>> {
        return registry.fields.map { entry ->
            val resolverClass = loadClass<ResolverBase<*>>(entry.tenantAPIData.resolverClass, "field ${entry.typeName}.${entry.fieldName}")
            val resolverBaseClass = loadClass<ResolverBase<*>>(entry.tenantAPIData.resolverBaseClass, "field resolver base for ${entry.typeName}.${entry.fieldName}")

            val provider = tenantCodeInjector.getProvider(resolverClass)
            val attribution = ExecutionAttribution.fromResolver(entry.tenantAPIData.resolverClass)

            // schema is passed here only because FieldExecutionContextFactory.of requires it to
            // look up argument/return types. This is one remaining schema dependency that will be
            // eliminated once the aggregation step embeds all type metadata into the registry JSON.
            val contextFactory = FieldExecutionContextFactory.of(
                resolverBaseClass = resolverBaseClass,
                reflectionLoader = reflectionLoader,
                schema = schema,
                typeName = entry.typeName,
                fieldName = entry.fieldName,
                grtConvFactory = grtConvFactory,
            )

            val (objectSelectionSet, querySelectionSet) = buildSelectionSets(
                entry = entry,
                schema = schema,
                attribution = attribution,
                contextFactory = contextFactory,
            )

            val resolverKClass = resolverClass.kotlin
            val resolverId = "${entry.typeName}.${entry.fieldName}"

            val executor: FieldResolverExecutor = if (entry.isBatching) {
                val batchResolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { it.name == "batchResolve" }
                    ?: error("Resolver ${entry.tenantAPIData.resolverClass} is marked isBatching=true but does not declare 'batchResolve'")
                log.info("- Adding batch field resolver for '{}.{}'", entry.typeName, entry.fieldName)
                FieldBatchResolverExecutorImpl(
                    objectSelectionSet = objectSelectionSet,
                    querySelectionSet = querySelectionSet,
                    isSelective = entry.isSelective,
                    resolver = provider,
                    batchResolveFn = batchResolveFn,
                    resolverId = resolverId,
                    reflectionLoader = reflectionLoader,
                    resolverContextFactory = contextFactory,
                    resolverName = entry.tenantAPIData.resolverClass,
                )
            } else {
                val resolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { fn -> fn.name == "resolve" }
                    ?: error("Resolver ${entry.tenantAPIData.resolverClass} does not declare 'resolve'")
                log.info("- Adding field resolver for '{}.{}'", entry.typeName, entry.fieldName)
                FieldUnbatchedResolverExecutorImpl(
                    objectSelectionSet = objectSelectionSet,
                    querySelectionSet = querySelectionSet,
                    isSelective = entry.isSelective,
                    resolver = provider,
                    resolveFn = resolveFn,
                    resolverId = resolverId,
                    reflectionLoader = reflectionLoader,
                    resolverContextFactory = contextFactory,
                    resolverName = entry.tenantAPIData.resolverClass,
                )
            }

            (entry.typeName to entry.fieldName) to executor
        }
    }

    // schema intentionally ignored — see class doc
    @Suppress("UNCHECKED_CAST")
    override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> {
        return registry.nodes.map { entry ->
            val resolverClass = loadClass<NodeResolverBase<*>>(entry.tenantAPIData.resolverClass, "node ${entry.typeName}")
            val resolverBaseClass = loadClass<NodeResolverBase<*>>(entry.tenantAPIData.resolverBaseClass, "node resolver base for ${entry.typeName}")

            val provider = tenantCodeInjector.getProvider(resolverClass)

            val reflectiveType = reflectionLoader.reflectionFor(entry.typeName) as Type<NodeObject>
            val contextFactory = NodeExecutionContextFactory(
                resolverBaseClass = resolverBaseClass,
                reflectionLoader = reflectionLoader,
                resultType = reflectiveType,
                grtConvFactory = grtConvFactory,
            )

            val resolverKClass = resolverClass.kotlin

            val executor: NodeResolverExecutor = if (entry.isBatching) {
                val batchResolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { fn -> fn.name == "batchResolve" }
                    ?: error("Resolver ${entry.tenantAPIData.resolverClass} is marked isBatching=true but does not declare 'batchResolve'")
                log.info("- Adding batch node resolver for '{}'", entry.typeName)
                NodeBatchResolverExecutorImpl(
                    resolver = provider,
                    batchResolveFunction = batchResolveFn,
                    typeName = entry.typeName,
                    reflectionLoader = reflectionLoader,
                    factory = contextFactory,
                    resolverName = entry.tenantAPIData.resolverClass,
                    isSelective = entry.isSelective,
                )
            } else {
                val resolveFn = resolverKClass.declaredMemberFunctions.firstOrNull { fn -> fn.name == "resolve" }
                    ?: error("Resolver ${entry.tenantAPIData.resolverClass} does not declare 'resolve'")
                log.info("- Adding node resolver for '{}'", entry.typeName)
                NodeUnbatchedResolverExecutorImpl(
                    resolver = provider,
                    resolveFunction = resolveFn,
                    typeName = entry.typeName,
                    reflectionLoader = reflectionLoader,
                    factory = contextFactory,
                    resolverName = entry.tenantAPIData.resolverClass,
                    isSelective = entry.isSelective,
                )
            }

            entry.typeName to executor
        }
    }

    private fun buildSelectionSets(
        entry: FieldEntry,
        schema: ViaductSchema,
        attribution: ExecutionAttribution,
        contextFactory: FieldExecutionContextFactory,
    ): Pair<RequiredSelectionSet?, RequiredSelectionSet?> {
        val objectSelections = entry.objectSelections?.let {
            SelectionsParser.parse(entry.typeName, it.selections)
        }
        val querySelections = entry.querySelections?.let {
            SelectionsParser.parse(schema.schema.queryType.name, it.selections)
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
        context: String
    ): Class<out T> {
        // KSP emits '.' as separator for nested classes; Class.forName requires '$'.
        // Try progressively moving the '.' → '$' boundary from the right until one resolves.
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
