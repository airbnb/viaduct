package viaduct.java.runtime.bridge

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
import viaduct.java.api.internal.BaseBatchedFieldResolver
import viaduct.java.api.internal.BaseBatchedNodeResolver
import viaduct.java.api.internal.BaseUnbatchedFieldResolver
import viaduct.java.api.internal.BaseUnbatchedNodeResolver
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
                val batchResolverProvider = requireBaseResolver(
                    resolverClass,
                    resolverProvider,
                    BaseBatchedFieldResolver::class.java,
                    "Batch field resolver",
                )
                log.info(
                    "- Adding entry for batch resolver for '{}.{}' to {} via {}",
                    typeName,
                    fieldName,
                    resolverName,
                    resolverClass.classLoader
                )
                FieldBatchResolverExecutorImpl(
                    resolver = batchResolverProvider,
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
                val unbatchedResolverProvider = requireBaseResolver(
                    resolverClass,
                    resolverProvider,
                    BaseUnbatchedFieldResolver::class.java,
                    "Field resolver",
                )
                log.info(
                    "- Adding entry for resolver for '{}.{}' to {} via {}",
                    typeName,
                    fieldName,
                    resolverName,
                    resolverClass.classLoader
                )
                JavaFieldResolverExecutorImpl(
                    resolver = unbatchedResolverProvider,
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
                val batchResolverProvider = requireBaseResolver(
                    resolverClass,
                    resolverProvider,
                    BaseBatchedNodeResolver::class.java,
                    "Batch node resolver",
                )
                log.info("- Adding node batch resolver entry for '{}' to '{}'.", typeName, resolverName)
                NodeBatchResolverExecutorImpl(
                    resolver = batchResolverProvider,
                    typeName = typeName,
                    resolverName = resolverName,
                    isSelective = nodeResolverForAnnotation.isSelective,
                    graphqlSchema = graphqlSchema,
                    classFinder = classFinder,
                )
            } else {
                val unbatchedResolverProvider = requireBaseResolver(
                    resolverClass,
                    resolverProvider,
                    BaseUnbatchedNodeResolver::class.java,
                    "Node resolver",
                )
                log.info("- Adding node resolver entry for '{}' to '{}'.", typeName, resolverName)
                JavaNodeResolverExecutorImpl(
                    resolver = unbatchedResolverProvider,
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
                    "its generated resolver base is out of date or incompatible with this runtime"
            )
        }
        return provider as Provider<T>
    }
}
