package viaduct.java.runtime.bridge

import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.CompletableFuture
import org.slf4j.LoggerFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.engine.api.spi.TenantModuleException
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.runtime.bootstrap.JavaResolverClassFinder
import viaduct.service.api.spi.TenantCodeInjector

/**
 * Bootstrapper for Java resolvers that implements the Viaduct [TenantModuleBootstrapper] interface.
 *
 * This class automatically discovers and registers Java resolvers using classpath scanning.
 * It mirrors the functionality of Kotlin's `ViaductTenantModuleBootstrapper` but for Java resolvers.
 *
 * ## Discovery Process
 *
 * 1. Scans for all classes annotated with `@ResolverFor` (generated base classes)
 * 2. For each base class, finds subclasses annotated with `@Resolver`
 * 3. Validates that exactly one implementation exists per field
 * 4. Wraps each resolver in a [JavaFieldResolverExecutor]
 * 5. Returns the mapping of field coordinates to executors
 *
 * ## Example Usage
 *
 * ```kotlin
 * val classFinder = DefaultJavaResolverClassFinder(
 *     tenantPackage = "com.mycompany.resolvers",
 *     grtPackagePrefix = "com.mycompany.grts"
 * )
 *
 * val bootstrapper = JavaModuleBootstrapper(
 *     classFinder = classFinder,
 *     injector = TenantCodeInjector.Naive
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
class JavaModuleBootstrapper(
    private val classFinder: JavaResolverClassFinder,
    private val injector: TenantCodeInjector,
) : TenantModuleBootstrapper {
    companion object {
        private val log = LoggerFactory.getLogger(JavaModuleBootstrapper::class.java)
    }

    // Factory for creating RequiredSelectionSets from @Resolver annotations
    private val requiredSelectionSetFactory = JavaRequiredSelectionSetFactory()

    override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Pair<String, String>, FieldResolverExecutor>> {
        val result = mutableMapOf<Pair<String, String>, FieldResolverExecutor>()

        // Get all classes annotated with @ResolverFor in tenant package
        val resolverForClasses = classFinder.resolverClassesInPackage()

        // Validate and cast to FieldResolverBase
        val resolverBaseClasses = resolverForClasses.map { clazz ->
            if (!FieldResolverBase::class.java.isAssignableFrom(clazz)) {
                throw TenantModuleException(
                    "Found @ResolverFor on class that doesn't implement FieldResolverBase: $clazz"
                )
            }
            @Suppress("UNCHECKED_CAST")
            clazz as Class<out FieldResolverBase<*, *, *, *, *>>
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

            // Find the resolve method
            val resolveMethod = findResolveMethod(resolverClass)
                ?: throw TenantModuleException(
                    "Resolver class $resolverClass does not have a 'resolve' method"
                )

            // Extract the Arguments and object value classes from the resolver base's generic type parameters
            val argumentsClass = extractArgumentsClass(baseClass)
            val objectValueClass = extractObjectValueClass(baseClass)

            // Create the executor
            val resolverId = "$typeName.$fieldName"
            val resolverName = resolverClass.name

            // Get the @Resolver annotation and create RequiredSelectionSets
            val resolverAnnotation = resolverClass.getAnnotation(Resolver::class.java)
            val requiredSelections = requiredSelectionSetFactory.mkRequiredSelectionSets(
                schema = schema,
                annotation = resolverAnnotation,
                resolverForType = typeName,
                resolverClassName = resolverName
            )

            log.info(
                "- Adding entry for resolver for '{}.{}' to {} via {}",
                typeName,
                fieldName,
                resolverName,
                resolverClass.classLoader
            )

            val executor = JavaFieldResolverExecutor(
                resolveFunction = { ctx -> invokeResolver(resolverProvider, resolveMethod, ctx) },
                resolverId = resolverId,
                resolverName = resolverName,
                argumentsClass = argumentsClass,
                objectSelectionSet = requiredSelections.objectSelections,
                querySelectionSet = requiredSelections.querySelections,
                objectValueClass = objectValueClass,
            )

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
        // Node resolver support is deferred - requires JavaNodeResolverExecutor bridge
        return emptyList()
    }

    /**
     * Extracts the object value class from a resolver base class's generic type parameters.
     *
     * FieldResolverBase<T, O, Q, A, S> — O (index 1) is the object value type.
     * Returns null if the type cannot be determined.
     */
    private fun extractObjectValueClass(baseClass: Class<*>): Class<*>? {
        for (iface in baseClass.genericInterfaces) {
            if (iface is ParameterizedType && iface.rawType == FieldResolverBase::class.java) {
                val typeArgs = iface.actualTypeArguments
                if (typeArgs.size >= 2) {
                    val objType = typeArgs[1]
                    if (objType is Class<*>) {
                        return objType
                    }
                }
            }
        }
        return null
    }

    /**
     * Extracts the Arguments class from a resolver base class's generic type parameters.
     *
     * FieldResolverBase<T, O, Q, A, S> — A (index 3) is the Arguments type.
     * Returns null if the type cannot be determined or is Arguments.None.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractArgumentsClass(baseClass: Class<*>): Class<out Arguments>? {
        for (iface in baseClass.genericInterfaces) {
            if (iface is ParameterizedType && iface.rawType == FieldResolverBase::class.java) {
                val typeArgs = iface.actualTypeArguments
                if (typeArgs.size >= 4) {
                    val argType = typeArgs[3]
                    if (argType is Class<*> && Arguments::class.java.isAssignableFrom(argType)) {
                        val argClass = argType as Class<out Arguments>
                        return if (argClass == Arguments.None::class.java) null else argClass
                    }
                }
            }
        }
        return null
    }

    /**
     * Finds the resolve method on the resolver class.
     *
     * Looks for a method named "resolve" that takes a Context parameter and returns CompletableFuture.
     * Prefers declared methods to inherited ones to avoid bridge method issues.
     */
    private fun findResolveMethod(resolverClass: Class<*>): Method? {
        // First try declared methods (to avoid bridge methods from generics)
        val declaredMethod = resolverClass.declaredMethods.firstOrNull { method ->
            method.name == "resolve" &&
                method.parameterCount == 1 &&
                CompletableFuture::class.java.isAssignableFrom(method.returnType) &&
                !method.isBridge
        }
        if (declaredMethod != null) {
            return declaredMethod
        }

        // Fall back to all methods (including inherited)
        return resolverClass.methods.firstOrNull { method ->
            method.name == "resolve" &&
                method.parameterCount == 1 &&
                CompletableFuture::class.java.isAssignableFrom(method.returnType) &&
                !method.isBridge
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
        provider: javax.inject.Provider<*>,
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
}
