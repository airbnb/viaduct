package viaduct.java.runtime.bootstrap

import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import javax.inject.Provider
import org.slf4j.LoggerFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantModuleException
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.GRT
import viaduct.java.runtime.bridge.DefaultResolverClassFinder
import viaduct.java.runtime.bridge.FieldBatchResolverExecutorImpl
import viaduct.java.runtime.bridge.JavaFieldResolverExecutorImpl
import viaduct.java.runtime.bridge.JavaNodeResolverExecutorImpl
import viaduct.java.runtime.bridge.NodeBatchResolverExecutorImpl
import viaduct.java.runtime.bridge.RequiredSelectionSetFactory
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.InputStreamSource

/**
 * [ExecutorFactory] for Java resolvers, built from a file-based [ExecutionRegistry].
 *
 * This is the Java twin of [viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory]. The
 * engine (via [viaduct.engine.runtime.tenantloading.ExecutionRegistryTenantAPIBootstrapper])
 * instantiates this class reflectively using the 3-arg constructor
 * `(CodeInjector, String grtPackagePrefix, InputStreamSource configSource)` named in each
 * `META-INF/viaduct/modules/<pkg>.json` registry file, then calls
 * [createFieldResolverExecutor] / [createNodeResolverExecutor] once per entry.
 *
 * Unlike the legacy `ModuleBootstrapper`, this factory does NOT scan the classpath: discovery
 * happens at build time (the Java APT registry extractor), and this factory constructs executors
 * purely from the already-discovered [FieldEntryConfig] / [NodeEntryConfig] config — including the
 * required selection sets, which are read from the registry JSON rather than the runtime `@Resolver`
 * annotation, keeping the registry as the single source of bootstrap data.
 *
 * The engine model carries tenant-specific bootstrap data as an opaque `tenantAPIData` map; this
 * factory reads the keys it owns ([resolverClass], [queryTypeName], [hasArguments]) via the typed
 * accessors below, mirroring how [viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory]
 * reads its own map.
 */
class ViaductJavaExecutorFactory(
    private val codeInjector: CodeInjector,
    private val grtPackagePrefix: String,
    @Suppress("UNUSED_PARAMETER") configSource: InputStreamSource,
) : ExecutorFactory {
    private val requiredSelectionSetFactory = RequiredSelectionSetFactory()

    // Resolves GRT/Arguments classes by name for the per-request InternalContext attached to GRTs.
    // The tenant package is irrelevant here: this factory discovers resolvers from the file-based
    // registry, so only the name-only lookups (grtClassForName/argumentClassForName) are used and
    // the scanner is never triggered.
    private val classFinder: ResolverClassFinder = DefaultResolverClassFinder(grtPackagePrefix, grtPackagePrefix)

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

        // Derive type classes from the registry entry + grtPackagePrefix (no schema scan needed).
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
            classFinder = classFinder,
        )

        return if (configData.isBatching) {
            val batchResolveMethod = findResolveMethod(resolverClass, "batchResolve")
                ?: throw TenantModuleException(
                    "Resolver class $resolverClass is annotated with isBatching=true but does not have a 'batchResolve' method",
                )
            log.info("- Adding batch field resolver for '{}.{}' to {}", configData.typeName, configData.fieldName, resolverName)
            FieldBatchResolverExecutorImpl(
                batchResolveFunction = { ctxList -> invokeBatchResolver(resolverProvider, batchResolveMethod, ctxList) },
                resolverId = resolverId,
                resolverName = resolverName,
                argumentsClass = argumentsClass,
                objectSelectionSet = requiredSelections.objectSelections,
                querySelectionSet = requiredSelections.querySelections,
                isSelective = configData.isSelective,
                objectValueClass = objectValueClass,
                queryValueClass = queryValueClass,
                graphqlSchema = schema.schema,
                classFinder = classFinder,
            )
        } else {
            val resolveMethod = findResolveMethod(resolverClass)
                ?: throw TenantModuleException(
                    "Resolver class $resolverClass does not have a 'resolve' method",
                )
            log.info("- Adding field resolver for '{}.{}' to {}", configData.typeName, configData.fieldName, resolverName)
            JavaFieldResolverExecutorImpl(
                resolveFunction = { ctx -> invokeResolver(resolverProvider, resolveMethod, ctx) },
                resolverId = resolverId,
                resolverName = resolverName,
                argumentsClass = argumentsClass,
                objectSelectionSet = requiredSelections.objectSelections,
                querySelectionSet = requiredSelections.querySelections,
                isSelective = configData.isSelective,
                objectValueClass = objectValueClass,
                queryValueClass = queryValueClass,
                graphqlSchema = schema.schema,
                classFinder = classFinder,
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
            val batchResolveMethod = findResolveMethod(resolverClass, "batchResolve")
                ?: throw TenantModuleException(
                    "Node resolver class $resolverClass is annotated with isBatching=true but does not have a 'batchResolve' method",
                )
            log.info("- Adding batch node resolver for '{}' to {}", configData.typeName, resolverName)
            NodeBatchResolverExecutorImpl(
                batchResolveFunction = { ctxList -> invokeNodeBatchResolver(resolverProvider, batchResolveMethod, ctxList) },
                typeName = configData.typeName,
                resolverName = resolverName,
                isSelective = configData.isSelective,
                graphqlSchema = graphqlSchema,
                classFinder = classFinder,
            )
        } else {
            val resolveMethod = findResolveMethod(resolverClass)
                ?: throw TenantModuleException(
                    "Node resolver class $resolverClass does not have a 'resolve' method",
                )
            log.info("- Adding node resolver for '{}' to {}", configData.typeName, resolverName)
            JavaNodeResolverExecutorImpl(
                resolveFunction = { ctx -> invokeNodeResolver(resolverProvider, resolveMethod, ctx) },
                typeName = configData.typeName,
                resolverName = resolverName,
                isSelective = configData.isSelective,
                graphqlSchema = graphqlSchema,
                classFinder = classFinder,
            )
        }
    }

    private fun grtClassForName(typeName: String): Class<out GRT> = classFinder.grtClassForName(typeName)

    private fun argumentClassForName(className: String): Class<out Arguments> = classFinder.argumentClassForName(className)

    private fun loadClass(
        fqn: String,
        context: String,
    ): Class<*> =
        try {
            Class.forName(fqn)
        } catch (e: ClassNotFoundException) {
            throw ClassNotFoundException("Cannot load class '$fqn' for $context", e)
        }

    private inline fun <T> tryOrNull(block: () -> T): T? =
        try {
            block()
        } catch (_: Exception) {
            null
        }

    /**
     * Finds a named method on [resolverClass] that takes one parameter and returns CompletableFuture.
     * Prefers declared methods to avoid bridge methods from generics erasure.
     */
    private fun findResolveMethod(
        resolverClass: Class<*>,
        name: String = "resolve",
    ): Method? {
        val declared = resolverClass.declaredMethods.firstOrNull { m ->
            m.name == name &&
                m.parameterCount == 1 &&
                CompletableFuture::class.java.isAssignableFrom(m.returnType) &&
                !m.isBridge
        }
        if (declared != null) return declared
        return resolverClass.methods.firstOrNull { m ->
            m.name == name &&
                m.parameterCount == 1 &&
                CompletableFuture::class.java.isAssignableFrom(m.returnType) &&
                !m.isBridge
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeResolver(
        provider: Provider<*>,
        resolveMethod: Method,
        context: FieldExecutionContext<*, *, *, *>,
    ): CompletableFuture<Any?> =
        try {
            val resolver = provider.get()
            val contextType = resolveMethod.parameterTypes[0]
            val wrappedContext = wrapContext(contextType, context)
            resolveMethod.invoke(resolver, wrappedContext) as CompletableFuture<Any?>
        } catch (e: Exception) {
            CompletableFuture<Any?>().apply { completeExceptionally(e) }
        }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBatchResolver(
        provider: Provider<*>,
        batchResolveMethod: Method,
        contexts: List<FieldExecutionContext<*, *, *, *>>,
    ): CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, *>> =
        try {
            val resolver = provider.get()
            val listParamElementType =
                (batchResolveMethod.genericParameterTypes[0] as? ParameterizedType)
                    ?.actualTypeArguments?.firstOrNull() as? Class<*>
            val wrappedContexts = if (listParamElementType != null) {
                contexts.map { wrapContext(listParamElementType, it) }
            } else {
                contexts
            }
            val wrappedToOriginal = IdentityHashMap<Any, FieldExecutionContext<*, *, *, *>>()
            wrappedContexts.zip(contexts).forEach { (wrapped, original) ->
                wrappedToOriginal[wrapped] = original
            }
            val future = batchResolveMethod.invoke(resolver, wrappedContexts) as CompletableFuture<Map<*, *>>
            future.thenApply { contextToValue ->
                contextToValue.entries.associate { (wrappedCtx, value) ->
                    val original = wrappedToOriginal[wrappedCtx]
                        ?: throw TenantModuleException(
                            "batchResolve returned a key that was not in the input context list: $wrappedCtx",
                        )
                    original to value
                }
            }
        } catch (e: Exception) {
            CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, *>>().apply { completeExceptionally(e) }
        }

    private fun wrapContext(
        contextType: Class<*>,
        context: FieldExecutionContext<*, *, *, *>,
    ): Any {
        val constructor = contextType.constructors.firstOrNull { ctor ->
            ctor.parameterCount == 1 &&
                FieldExecutionContext::class.java.isAssignableFrom(ctor.parameterTypes[0])
        } ?: throw IllegalStateException(
            "Context class ${contextType.name} does not have a constructor taking FieldExecutionContext",
        )
        return constructor.newInstance(context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeNodeResolver(
        provider: Provider<*>,
        resolveMethod: Method,
        context: NodeExecutionContext<*>,
    ): CompletableFuture<Any?> =
        try {
            val resolver = provider.get()
            val contextType = resolveMethod.parameterTypes[0]
            val arg = wrapNodeContext(contextType, context)
            resolveMethod.invoke(resolver, arg) as CompletableFuture<Any?>
        } catch (e: Exception) {
            CompletableFuture<Any?>().apply { completeExceptionally(e) }
        }

    @Suppress("UNCHECKED_CAST")
    private fun invokeNodeBatchResolver(
        provider: Provider<*>,
        batchResolveMethod: Method,
        contexts: List<NodeExecutionContext<*>>,
    ): CompletableFuture<Any?> =
        try {
            val resolver = provider.get()
            val listParamElementType =
                (batchResolveMethod.genericParameterTypes[0] as? ParameterizedType)
                    ?.actualTypeArguments?.firstOrNull() as? Class<*>
            val wrappedContexts = if (listParamElementType != null) {
                contexts.map { wrapNodeContext(listParamElementType, it) }
            } else {
                contexts
            }
            batchResolveMethod.invoke(resolver, wrappedContexts) as CompletableFuture<Any?>
        } catch (e: Exception) {
            CompletableFuture<Any?>().apply { completeExceptionally(e) }
        }

    private fun wrapNodeContext(
        contextType: Class<*>,
        context: NodeExecutionContext<*>,
    ): Any {
        val constructor = contextType.constructors.firstOrNull { ctor ->
            ctor.parameterCount == 1 &&
                NodeExecutionContext::class.java.isAssignableFrom(ctor.parameterTypes[0])
        }
        if (constructor != null) {
            return constructor.newInstance(context)
        }
        return context
    }

    companion object {
        private val log = LoggerFactory.getLogger(ViaductJavaExecutorFactory::class.java)
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
