package viaduct.java.runtime.bridge

import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import org.slf4j.LoggerFactory
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.TenantModuleBootstrapper
import viaduct.engine.api.TenantModuleException
import viaduct.engine.api.ViaductSchema
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.resolvers.FieldResolverBase
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

            // Create the executor
            val resolverId = "$typeName.$fieldName"
            val resolverName = resolverClass.name

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
     */
    @Suppress("UNCHECKED_CAST")
    private fun invokeResolver(
        provider: javax.inject.Provider<*>,
        resolveMethod: Method,
        context: FieldExecutionContext<*, *, *, *>,
    ): CompletableFuture<Any?> {
        return try {
            val resolver = provider.get()
            resolveMethod.invoke(resolver, context) as CompletableFuture<Any?>
        } catch (e: Exception) {
            CompletableFuture<Any?>().apply { completeExceptionally(e) }
        }
    }
}
