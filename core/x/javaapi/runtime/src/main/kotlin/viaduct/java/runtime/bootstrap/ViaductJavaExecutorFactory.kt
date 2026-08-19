package viaduct.java.runtime.bootstrap

import graphql.language.FragmentDefinition
import javax.inject.Provider
import org.slf4j.LoggerFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.parse.CachedDocumentParser
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantModuleException
import viaduct.java.api.internal.BaseBatchedFieldResolver
import viaduct.java.api.internal.BaseBatchedNodeResolver
import viaduct.java.api.internal.BaseUnbatchedFieldResolver
import viaduct.java.api.internal.BaseUnbatchedNodeResolver
import viaduct.java.api.internal.OutputBuilderTypeChecker
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.GRT
import viaduct.java.runtime.bridge.FieldBatchResolverExecutorImpl
import viaduct.java.runtime.bridge.JavaFieldResolverExecutorImpl
import viaduct.java.runtime.bridge.JavaNodeResolverExecutorImpl
import viaduct.java.runtime.bridge.NodeBatchResolverExecutorImpl
import viaduct.java.runtime.bridge.RequiredSelectionSetFactory
import viaduct.service.api.spi.CodeInjector

/**
 * [ExecutorFactory] for Java resolvers, built from a file-based [ExecutionRegistry].
 *
 * This is the Java twin of [viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory]. The
 * engine (via [viaduct.engine.runtime.tenantloading.ModuleConfigBootstrapper])
 * instantiates this class reflectively using the production
 * `(CodeInjector, ExecutionRegistryConfigFile)` constructor named in each
 * `META-INF/viaduct/modules/<pkg>.json` registry file. Production uses the fixed Java GRT package;
 * tests may override that package through the primary three-argument constructor. The engine then
 * calls [createFieldResolverExecutor] / [createNodeResolverExecutor] once per entry.
 *
 * Discovery happens at build time in the Java APT registry extractor. This factory constructs
 * executors purely from the already-discovered [FieldEntryConfig] / [NodeEntryConfig] config,
 * keeping the registry as the single source of bootstrap data.
 *
 * The engine model carries tenant-specific bootstrap data as an opaque `tenantAPIData` map; this
 * factory reads the keys it owns ([resolverClass], [queryTypeName], [hasArguments]) via the typed
 * accessors below, mirroring how [viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory]
 * reads its own map.
 */
class ViaductJavaExecutorFactory(
    private val codeInjector: CodeInjector,
    private val grtPackagePrefix: String,
    private val registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    /** Production constructor using the compile-time Java GRT package. */
    constructor(codeInjector: CodeInjector, registry: ExecutionRegistryConfigFile) :
        this(codeInjector, JAVA_GRT_PACKAGE_PREFIX, registry)

    private val requiredSelectionSetFactory = RequiredSelectionSetFactory()

    private val namedFragments: Map<String, FragmentDefinition> by lazy {
        registry.namedFragments
            .flatMap { CachedDocumentParser.parseDocument(it).getDefinitionsOfType(FragmentDefinition::class.java) }
            .associateBy { it.name }
    }

    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema,
    ): FieldResolverExecutor {
        val apiData = configData.tenantAPIData.toFieldAPIData()
        val resolverClass = loadClass(
            apiData.resolverClass,
            "field ${configData.typeName}.${configData.fieldName}",
        )

        val resolverProvider = try {
            codeInjector.getProvider(resolverClass)
        } catch (e: NoClassDefFoundError) {
            throw TenantModuleException("Resolver class $resolverClass could not be injected", e)
        }

        // Derive type classes from the registry entry and fixed package (no schema scan needed).
        val objectValueClass = tryOrNull { grtClassForName(configData.typeName) }
        val queryValueClass = tryOrNull { grtClassForName(apiData.queryTypeName) }
        val argumentsClass = if (apiData.hasArguments) {
            val capitalizedField = configData.fieldName.replaceFirstChar { it.uppercase() }
            tryOrNull { argumentClassForName("${configData.typeName}_${capitalizedField}_Arguments") }
        } else {
            null
        }

        val resolverId = "${configData.typeName}.${configData.fieldName}"
        val resolverName = resolverClass.name

        // Build the required selection sets from the file-based registry entry. The selection
        // fragments and variable declarations live in the registry JSON (emitted by the APT from
        // the @Resolver annotation at build time), so the JSON is the single source of bootstrap
        // data — we do not re-read the runtime annotation here. The nested @Variables provider,
        // which the registry does not capture, is still discovered reflectively from the class.
        val requiredSelections = requiredSelectionSetFactory.mkRequiredSelectionSets(
            schema = schema,
            entry = configData,
            resolverClass = resolverClass,
            injector = codeInjector,
            argumentsClass = argumentsClass,
            grtPackagePrefix = grtPackagePrefix,
        )

        return if (configData.isBatching) {
            val batchResolverProvider = requireBaseResolver(
                resolverClass,
                resolverProvider,
                BaseBatchedFieldResolver::class.java,
                "Batch field resolver",
            )
            log.info("- Adding batch field resolver for '{}.{}' to {}", configData.typeName, configData.fieldName, resolverName)
            FieldBatchResolverExecutorImpl(
                resolver = batchResolverProvider,
                resolverId = resolverId,
                resolverName = resolverName,
                argumentsClass = argumentsClass,
                objectSelectionSet = requiredSelections.objectSelections,
                querySelectionSet = requiredSelections.querySelections,
                isSelective = configData.isSelective,
                objectValueClass = objectValueClass,
                queryValueClass = queryValueClass,
                graphqlSchema = schema.schema,
                grtPackagePrefix = grtPackagePrefix,
                knownFragments = namedFragments,
            )
        } else {
            val unbatchedResolverProvider = requireBaseResolver(
                resolverClass,
                resolverProvider,
                BaseUnbatchedFieldResolver::class.java,
                "Field resolver",
            )
            log.info("- Adding field resolver for '{}.{}' to {}", configData.typeName, configData.fieldName, resolverName)
            JavaFieldResolverExecutorImpl(
                resolver = unbatchedResolverProvider,
                resolverId = resolverId,
                resolverName = resolverName,
                argumentsClass = argumentsClass,
                objectSelectionSet = requiredSelections.objectSelections,
                querySelectionSet = requiredSelections.querySelections,
                isSelective = configData.isSelective,
                objectValueClass = objectValueClass,
                queryValueClass = queryValueClass,
                graphqlSchema = schema.schema,
                grtPackagePrefix = grtPackagePrefix,
                knownFragments = namedFragments,
            )
        }
    }

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema,
    ): NodeResolverExecutor {
        val resolverClass = loadClass(
            configData.tenantAPIData.toNodeAPIData().resolverClass,
            "node ${configData.typeName}",
        )

        val resolverProvider = try {
            codeInjector.getProvider(resolverClass)
        } catch (e: NoClassDefFoundError) {
            throw TenantModuleException("Node resolver class $resolverClass could not be injected", e)
        }

        val resolverName = resolverClass.name
        val graphqlSchema = schema.schema

        return if (configData.isBatching) {
            val batchResolverProvider = requireBaseResolver(
                resolverClass,
                resolverProvider,
                BaseBatchedNodeResolver::class.java,
                "Batch node resolver",
            )
            log.info("- Adding batch node resolver for '{}' to {}", configData.typeName, resolverName)
            NodeBatchResolverExecutorImpl(
                resolver = batchResolverProvider,
                typeName = configData.typeName,
                resolverName = resolverName,
                isSelective = configData.isSelective,
                graphqlSchema = graphqlSchema,
                grtPackagePrefix = grtPackagePrefix,
                knownFragments = namedFragments,
            )
        } else {
            val unbatchedResolverProvider = requireBaseResolver(
                resolverClass,
                resolverProvider,
                BaseUnbatchedNodeResolver::class.java,
                "Node resolver",
            )
            log.info("- Adding node resolver for '{}' to {}", configData.typeName, resolverName)
            JavaNodeResolverExecutorImpl(
                resolver = unbatchedResolverProvider,
                typeName = configData.typeName,
                resolverName = resolverName,
                isSelective = configData.isSelective,
                graphqlSchema = graphqlSchema,
                grtPackagePrefix = grtPackagePrefix,
                knownFragments = namedFragments,
            )
        }
    }

    private fun grtClassForName(typeName: String): Class<out GRT> = loadTypedClass("$grtPackagePrefix.$typeName", GRT::class.java)

    private fun argumentClassForName(className: String): Class<out Arguments> = loadTypedClass("$grtPackagePrefix.$className", Arguments::class.java)

    private fun loadClass(
        fqn: String,
        context: String,
    ): Class<*> =
        try {
            Class.forName(fqn)
        } catch (e: ClassNotFoundException) {
            throw ClassNotFoundException("Cannot load class '$fqn' for $context", e)
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> loadTypedClass(
        fqn: String,
        expectedType: Class<T>,
    ): Class<out T> {
        val clazz = Class.forName(fqn)
        require(expectedType.isAssignableFrom(clazz)) {
            "Class $fqn exists but does not implement ${expectedType.simpleName}"
        }
        return clazz as Class<out T>
    }

    private inline fun <T> tryOrNull(block: () -> T): T? =
        try {
            block()
        } catch (_: Exception) {
            null
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> requireBaseResolver(
        resolverClass: Class<*>,
        provider: Provider<*>,
        baseResolverClass: Class<T>,
        resolverDescription: String,
    ): Provider<T> {
        if (!baseResolverClass.isAssignableFrom(resolverClass)) {
            throw TenantModuleException(
                "$resolverDescription ${resolverClass.name} does not implement ${baseResolverClass.simpleName}; " +
                    "its generated resolver base is out of date or incompatible with this runtime",
            )
        }
        return provider as Provider<T>
    }

    companion object {
        private val log = LoggerFactory.getLogger(ViaductJavaExecutorFactory::class.java)

        const val JAVA_GRT_PACKAGE_PREFIX = OutputBuilderTypeChecker.GENERATED_GRT_PACKAGE
    }
}

/**
 * Typed view over the opaque `tenantAPIData` map of a field [FieldEntryConfig], carrying the keys
 * this factory owns. The Java APT extractor writes these keys; the engine treats the map as opaque.
 */
private data class JavaFieldAPIData(
    val resolverClass: String,
    val resolverBaseClass: String,
    val returnTypeName: String?,
    val hasArguments: Boolean,
    val queryTypeName: String,
)

private fun Map<String, Any?>.toFieldAPIData(): JavaFieldAPIData =
    JavaFieldAPIData(
        resolverClass = this["resolverClass"] as String,
        resolverBaseClass = this["resolverBaseClass"] as String,
        returnTypeName = this["returnTypeName"] as String?,
        hasArguments = this["hasArguments"] as? Boolean ?: false,
        queryTypeName = this["queryTypeName"] as String,
    )

/** Typed view over the opaque `tenantAPIData` map of a node [NodeEntryConfig]. */
private data class JavaNodeAPIData(
    val resolverClass: String,
    val resolverBaseClass: String,
)

private fun Map<String, Any?>.toNodeAPIData(): JavaNodeAPIData =
    JavaNodeAPIData(
        resolverClass = this["resolverClass"] as String,
        resolverBaseClass = this["resolverBaseClass"] as String,
    )
