package viaduct.java.runtime.bridge

import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import javax.inject.Provider
import org.slf4j.LoggerFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.engine.api.spi.TenantModuleException
import viaduct.java.api.annotations.NodeResolverFor
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.resolvers.NodeResolverBase
import viaduct.service.api.spi.CodeInjector

/**
 * Bootstrapper for Java resolvers that implements the Viaduct [TenantModuleBootstrapper] interface.
 *
 * This class automatically discovers and registers Java resolvers using classpath scanning.
 *
 * ## Discovery Process
 *
 * 1. Scans for all classes annotated with `@ResolverFor` (generated base classes)
 * 2. For each base class, finds subclasses annotated with `@Resolver`
 * 3. Validates that exactly one implementation exists per field
 * 4. Wraps each resolver in a [JavaFieldResolverExecutorImpl]
 * 5. Returns the mapping of field coordinates to executors
 *
 * ## Example Usage
 *
 * ```kotlin
 * val classFinder = DefaultResolverClassFinder(
 *     tenantPackage = "com.mycompany.resolvers",
 *     grtPackagePrefix = "com.mycompany.grts"
 * )
 *
 * val bootstrapper = ModuleBootstrapper(
 *     classFinder = classFinder,
 *     injector = CodeInjector.Naive
 * )
 * ```
 *
 * ## Resolver Requirements
 *
 * For a resolver to be discovered:
 * - The base class must be annotated with `@ResolverFor(typeName, fieldName)`
 * - The base class must implement [FieldResolverBase]
 * - The implementation must extend the base class
 * - The implementation must be annotated with `@Resolver`
 * - The implementation must have a `resolve` method
 *
 * @param classFinder the class finder for discovering resolver classes
 * @param injector the code injector for creating resolver instances
 */
class ModuleBootstrapper(
    private val classFinder: ResolverClassFinder,
    private val injector: CodeInjector,
) : TenantModuleBootstrapper {
    companion object {
        private val log = LoggerFactory.getLogger(ModuleBootstrapper::class.java)
    }

    // Factory for creating RequiredSelectionSets from @Resolver annotations
    private val requiredSelectionSetFactory = RequiredSelectionSetFactory()

    override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Pair<String, String>, FieldResolverExecutor>> {
        val result = mutableMapOf<Pair<String, String>, FieldResolverExecutor>()

        // Get all classes annotated with @ResolverFor in tenant package
        val resolverForClasses = classFinder.resolverClassesInPackage()

        // Validate that each class implements FieldResolverBase
        val resolverBaseClasses = resolverForClasses.map { clazz ->
            if (!FieldResolverBase::class.java.isAssignableFrom(clazz)) {
                throw TenantModuleException(
                    "Found @ResolverFor on class that doesn't implement FieldResolverBase: $clazz"
                )
            }
            clazz
        }

        // For each base class, find @Resolver implementations
        for (baseClass in resolverBaseClasses) {
            val resolverForAnnotation = baseClass.getAnnotation(ResolverFor::class.java)
                ?: throw TenantModuleException(
                    "ResolverBase class $baseClass does not have a @ResolverFor annotation"
                )

            val typeName = resolverForAnnotation.typeName
            val fieldName = resolverForAnnotation.fieldName

            // Validate field exists in schema
            val objectType = schema.schema.getObjectType(typeName)
            if (objectType == null) {
                val type = schema.schema.getType(typeName)
                if (type != null) {
                    log.warn("Found resolver code for type {} which is not a GraphQL Object type.", typeName)
                } else {
                    log.warn(
                        "Found resolver code for {}.{}, which is an undefined field in the schema.",
                        typeName,
                        fieldName
                    )
                }
                continue
            }

            val fieldDef = objectType.getFieldDefinition(fieldName)
            if (fieldDef == null) {
                log.warn(
                    "Found resolver code for {}.{}, which is an undefined field in the schema.",
                    typeName,
                    fieldName
                )
                continue
            }

            // Find all @Resolver subclasses
            val subTypes = classFinder.getSubTypesOf(FieldResolverBase::class.java)
            val resolverClasses = subTypes.filter { subType ->
                baseClass.isAssignableFrom(subType) && subType.isAnnotationPresent(Resolver::class.java)
            }

            if (resolverClasses.size != 1) {
                // Skip if no implementation or multiple implementations found
                if (resolverClasses.isEmpty()) {
                    log.debug("No @Resolver implementation found for {}.{}", typeName, fieldName)
                } else {
                    log.warn(
                        "Expected exactly one resolver implementation for {}.{}, found {}: {}",
                        typeName,
                        fieldName,
                        resolverClasses.size,
                        resolverClasses
                    )
                }
                continue
            }

            val resolverClass = resolverClasses.first()

            // Get provider for resolver instances
            val resolverProvider = try {
                injector.getProvider(resolverClass)
            } catch (e: NoClassDefFoundError) {
                throw TenantModuleException("Resolver class $resolverClass could not be injected", e)
            }

            // Derive type classes from schema and class finder (not from generic type positions)
            val objectValueClass = tryOrNull { classFinder.grtClassForName(typeName) }
            val queryValueClass = tryOrNull { classFinder.grtClassForName(schema.schema.queryType.name) }
            val argumentsClass = if (fieldDef.arguments.isNotEmpty()) {
                val capitalizedField = fieldName.replaceFirstChar { it.uppercase() }
                tryOrNull { classFinder.argumentClassForName("${typeName}_${capitalizedField}_Arguments") }
            } else {
                null
            }

            // Create the executor
            val resolverId = "$typeName.$fieldName"
            val resolverName = resolverClass.name

            // Get the @Resolver annotation and create RequiredSelectionSets
            val resolverAnnotation = resolverClass.getAnnotation(Resolver::class.java)
            val requiredSelections = requiredSelectionSetFactory.mkRequiredSelectionSets(
                schema = schema,
                annotation = resolverAnnotation,
                resolverForType = typeName,
                resolverClass = resolverClass,
                injector = injector,
                argumentsClass = argumentsClass,
                classFinder = classFinder,
            )

            val executor = if (resolverForAnnotation.isBatching) {
                val batchResolveMethod = findResolveMethod(resolverClass, "batchResolve")
                    ?: throw TenantModuleException(
                        "Resolver class $resolverClass is annotated with isBatching=true but does not have a 'batchResolve' method"
                    )
                log.info(
                    "- Adding entry for batch resolver for '{}.{}' to {} via {}",
                    typeName,
                    fieldName,
                    resolverName,
                    resolverClass.classLoader
                )
                FieldBatchResolverExecutorImpl(
                    batchResolveFunction = { ctxList -> invokeBatchResolver(resolverProvider, batchResolveMethod, ctxList) },
                    resolverId = resolverId,
                    resolverName = resolverName,
                    argumentsClass = argumentsClass,
                    objectSelectionSet = requiredSelections.objectSelections,
                    querySelectionSet = requiredSelections.querySelections,
                    isSelective = resolverForAnnotation.isSelective,
                    objectValueClass = objectValueClass,
                    queryValueClass = queryValueClass,
                    graphqlSchema = schema.schema,
                    classFinder = classFinder,
                )
            } else {
                val resolveMethod = findResolveMethod(resolverClass)
                    ?: throw TenantModuleException(
                        "Resolver class $resolverClass does not have a 'resolve' method"
                    )
                log.info(
                    "- Adding entry for resolver for '{}.{}' to {} via {}",
                    typeName,
                    fieldName,
                    resolverName,
                    resolverClass.classLoader
                )
                JavaFieldResolverExecutorImpl(
                    resolveFunction = { ctx -> invokeResolver(resolverProvider, resolveMethod, ctx) },
                    resolverId = resolverId,
                    resolverName = resolverName,
                    argumentsClass = argumentsClass,
                    objectSelectionSet = requiredSelections.objectSelections,
                    querySelectionSet = requiredSelections.querySelections,
                    isSelective = resolverForAnnotation.isSelective,
                    objectValueClass = objectValueClass,
                    queryValueClass = queryValueClass,
                    graphqlSchema = schema.schema,
                    classFinder = classFinder,
                )
            }

            val coordinate = typeName to fieldName
            result.put(coordinate, executor)?.let { existing ->
                throw RuntimeException(
                    "Duplicate resolver for type $typeName and field $fieldName. " +
                        "Found $existing in class '$resolverName'."
                )
            }
        }

        return result.entries.map { it.key to it.value }
    }

    override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> {
        val result = mutableMapOf<String, NodeResolverExecutor>()

        val nodeResolverForClasses = classFinder.nodeResolverForClassesInPackage()

        val nodeResolverBaseClasses = nodeResolverForClasses.map { clazz ->
            if (!NodeResolverBase::class.java.isAssignableFrom(clazz)) {
                throw TenantModuleException(
                    "Found @NodeResolverFor on class that doesn't implement NodeResolverBase: $clazz"
                )
            }
            @Suppress("UNCHECKED_CAST")
            clazz as Class<out NodeResolverBase<*>>
        }

        for (baseClass in nodeResolverBaseClasses) {
            val nodeResolverForAnnotation = baseClass.getAnnotation(NodeResolverFor::class.java)
                ?: throw TenantModuleException(
                    "NodeResolverBase class $baseClass does not have a @NodeResolverFor annotation"
                )

            val typeName = nodeResolverForAnnotation.typeName

            val nodeType = schema.schema.getObjectType(typeName)
            if (nodeType == null) {
                if (schema.schema.getType(typeName) == null) {
                    log.warn("Found node resolver code for {} which is unknown in the schema.", typeName)
                } else {
                    log.warn("Found resolver code for type {} which is not a GraphQL Object type.", typeName)
                }
                continue
            } else if (nodeType.interfaces.none { it.name == "Node" }) {
                log.warn("Found node resolver for {} which does not implement Node.", typeName)
                continue
            }

            val subTypes = classFinder.getSubTypesOf(NodeResolverBase::class.java)
            val allSubclasses = subTypes.filter { subType ->
                baseClass.isAssignableFrom(subType) && subType != baseClass
            }
            val resolverClasses = allSubclasses.filter { it.isAnnotationPresent(Resolver::class.java) }

            if (resolverClasses.isEmpty()) {
                if (allSubclasses.isNotEmpty()) {
                    throw TenantModuleException(
                        "Found ${allSubclasses.size} subclass(es) of node resolver base for $typeName " +
                            "(${allSubclasses.map { it.name }}), but none are annotated with @Resolver. " +
                            "Add @Resolver to the active implementation."
                    )
                }
                continue
            }
            if (resolverClasses.size > 1) {
                throw TenantModuleException(
                    "Expected at most one @Resolver-annotated implementation for node resolver $typeName, " +
                        "found ${resolverClasses.size}: ${resolverClasses.map { it.name }}"
                )
            }

            val resolverClass = resolverClasses.first()
            val resolverAnnotation = resolverClass.getAnnotation(Resolver::class.java)
            if (resolverAnnotation != null) {
                if (resolverAnnotation.objectValueFragment.isNotBlank()) {
                    throw TenantModuleException(
                        "Node resolver $resolverClass cannot specify @Resolver(objectValueFragment=...): " +
                            "node resolvers do not have an object value."
                    )
                }
                if (resolverAnnotation.queryValueFragment.isNotBlank()) {
                    throw TenantModuleException(
                        "Node resolver $resolverClass cannot specify @Resolver(queryValueFragment=...): " +
                            "node resolvers do not support query value fragments."
                    )
                }
                if (resolverAnnotation.variables.isNotEmpty()) {
                    throw TenantModuleException(
                        "Node resolver $resolverClass cannot specify @Resolver(variables=...): " +
                            "node resolvers do not support variables."
                    )
                }
            }
            val resolverProvider = try {
                injector.getProvider(resolverClass)
            } catch (e: NoClassDefFoundError) {
                throw TenantModuleException("Node resolver class $resolverClass could not be injected", e)
            }

            val resolverName = resolverClass.name
            val graphqlSchema = schema.schema

            val executor = if (nodeResolverForAnnotation.isBatching) {
                val batchResolveMethod = findResolveMethod(resolverClass, "batchResolve")
                    ?: throw TenantModuleException(
                        "Node resolver class $resolverClass is annotated with isBatching=true but does not have a 'batchResolve' method"
                    )
                log.info("- Adding node batch resolver entry for '{}' to '{}'.", typeName, resolverName)
                NodeBatchResolverExecutorImpl(
                    batchResolveFunction = { ctxList -> invokeNodeBatchResolver(resolverProvider, batchResolveMethod, ctxList) },
                    typeName = typeName,
                    resolverName = resolverName,
                    isSelective = nodeResolverForAnnotation.isSelective,
                    graphqlSchema = graphqlSchema,
                    classFinder = classFinder,
                )
            } else {
                val resolveMethod = findResolveMethod(resolverClass)
                    ?: throw TenantModuleException(
                        "Node resolver class $resolverClass does not have a 'resolve' method"
                    )
                log.info("- Adding node resolver entry for '{}' to '{}'.", typeName, resolverName)
                JavaNodeResolverExecutorImpl(
                    resolveFunction = { ctx -> invokeNodeResolver(resolverProvider, resolveMethod, ctx) },
                    typeName = typeName,
                    resolverName = resolverName,
                    isSelective = nodeResolverForAnnotation.isSelective,
                    graphqlSchema = graphqlSchema,
                    classFinder = classFinder,
                )
            }

            result.put(typeName, executor)?.let { existing ->
                throw RuntimeException(
                    "Duplicate node resolver for type $typeName. Found $existing in class '$resolverName'."
                )
            }
        }

        return result.entries.map { it.key to it.value }
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
        name: String = "resolve"
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

    /**
     * Invokes the resolve method on a fresh resolver instance.
     *
     * Each invocation creates a new resolver instance via the provider, matching Viaduct's
     * per-invocation instantiation model.
     *
     * The resolve method expects a resolver-specific Context class that wraps FieldExecutionContext.
     * This method finds that Context class and creates an instance wrapping the provided context.
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeResolver(
        provider: Provider<*>,
        resolveMethod: Method,
        context: FieldExecutionContext<*, *, *, *>,
    ): CompletableFuture<Any?> {
        return try {
            val resolver = provider.get()
            // The resolve method expects a Context class that wraps FieldExecutionContext
            val contextType = resolveMethod.parameterTypes[0]
            val wrappedContext = wrapContext(contextType, context)
            resolveMethod.invoke(resolver, wrappedContext) as CompletableFuture<Any?>
        } catch (e: Exception) {
            CompletableFuture<Any?>().apply { completeExceptionally(e) }
        }
    }

    /**
     * Invokes the batchResolve method on a fresh resolver instance.
     *
     * Wraps each context in the resolver-specific Context class, then calls
     * batchResolve(List<Context>). The method returns CompletableFuture<Map<Context, T>>.
     * The Context keys are then remapped to their inner FieldExecutionContext so that the caller
     * can do key-based lookup regardless of the Map type returned by the tenant (e.g. HashMap vs
     * LinkedHashMap).
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeBatchResolver(
        provider: Provider<*>,
        batchResolveMethod: Method,
        contexts: List<FieldExecutionContext<*, *, *, *>>,
    ): CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, *>> {
        return try {
            val resolver = provider.get()
            // Wrap each FieldExecutionContext in the resolver's inner Context.
            // batchResolveMethod takes List<Context> — extract the Context class from generics.
            val listParamElementType =
                (batchResolveMethod.genericParameterTypes[0] as? ParameterizedType)
                    ?.actualTypeArguments?.firstOrNull() as? Class<*>
            val wrappedContexts = if (listParamElementType != null) {
                contexts.map { wrapContext(listParamElementType, it) }
            } else {
                // Fallback: pass contexts unwrapped (should not happen with well-formed codegen)
                contexts
            }
            // Build a reverse lookup: wrapped Context instance → original FieldExecutionContext.
            // Uses IdentityHashMap to match by reference, not equals(), which is important since
            // generated Context classes don't override equals().
            val wrappedToOriginal = IdentityHashMap<Any, FieldExecutionContext<*, *, *, *>>()
            wrappedContexts.zip(contexts).forEach { (wrapped, original) ->
                wrappedToOriginal[wrapped] = original
            }
            val future = batchResolveMethod.invoke(resolver, wrappedContexts) as CompletableFuture<Map<*, *>>
            future.thenApply { contextToValue ->
                contextToValue.entries.associate { (wrappedCtx, value) ->
                    val original = wrappedToOriginal[wrappedCtx]
                        ?: throw TenantModuleException(
                            "batchResolve returned a key that was not in the input context list: $wrappedCtx"
                        )
                    original to value
                }
            }
        } catch (e: Exception) {
            CompletableFuture<Map<FieldExecutionContext<*, *, *, *>, *>>().apply { completeExceptionally(e) }
        }
    }

    /**
     * Wraps a FieldExecutionContext in the resolver's Context class.
     *
     * Generated resolver base classes have an inner Context class that wraps FieldExecutionContext.
     * This method creates an instance of that Context class with the provided context.
     */
    private fun wrapContext(
        contextType: Class<*>,
        context: FieldExecutionContext<*, *, *, *>
    ): Any {
        // Find the constructor that takes FieldExecutionContext
        val constructor = contextType.constructors.firstOrNull { ctor ->
            ctor.parameterCount == 1 &&
                FieldExecutionContext::class.java.isAssignableFrom(ctor.parameterTypes[0])
        } ?: throw IllegalStateException(
            "Context class ${contextType.name} does not have a constructor taking FieldExecutionContext"
        )

        return constructor.newInstance(context)
    }

    /**
     * Invokes the resolve method on a node resolver instance.
     *
     * The resolve method takes a Context wrapping NodeExecutionContext. If codegen wraps the
     * context (via a generated inner Context class), the constructor is used to wrap it; otherwise
     * the context is passed directly.
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeNodeResolver(
        provider: javax.inject.Provider<*>,
        resolveMethod: Method,
        context: NodeExecutionContext<*>,
    ): CompletableFuture<Any?> {
        return try {
            val resolver = provider.get()
            val contextType = resolveMethod.parameterTypes[0]
            val arg = wrapNodeContext(contextType, context)
            resolveMethod.invoke(resolver, arg) as CompletableFuture<Any?>
        } catch (e: Exception) {
            CompletableFuture<Any?>().apply { completeExceptionally(e) }
        }
    }

    /**
     * Invokes the batchResolve method on a node resolver instance.
     *
     * Wraps each NodeExecutionContext in the resolver's inner Context class if applicable, then
     * calls batchResolve(List<Context>).
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeNodeBatchResolver(
        provider: javax.inject.Provider<*>,
        batchResolveMethod: Method,
        contexts: List<NodeExecutionContext<*>>,
    ): CompletableFuture<Any?> {
        return try {
            val resolver = provider.get()
            val listParamElementType =
                (batchResolveMethod.genericParameterTypes[0] as? java.lang.reflect.ParameterizedType)
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
    }

    /**
     * Wraps a NodeExecutionContext in the resolver's Context class (if one exists).
     *
     * If the contextType has a constructor taking NodeExecutionContext, creates an instance of it.
     * Otherwise returns the context directly (for resolvers that use NodeExecutionContext directly).
     */
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
        // No wrapping constructor — pass the context directly
        return context
    }
}
