package viaduct.java.runtime.bridge

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.types.Arguments

/**
 * Kotlin bridge that wraps a Java resolver and implements [FieldResolverExecutor]
 * for the Viaduct engine.
 *
 * This bridge converts between:
 * - Java CompletableFuture <-> Kotlin suspend functions
 * - Java FieldExecutionContext <-> Kotlin EngineExecutionContext
 * - Engine argument maps <-> Typed Java Arguments instances
 *
 * @param resolveFunction A function that takes a FieldExecutionContext and returns a CompletableFuture
 * @param resolverId Unique identifier for this resolver (e.g., "Query.greeting")
 * @param resolverName Human-readable resolver name for metadata
 * @param argumentsClass The Java Arguments class for this resolver, used to create typed instances
 *        from the engine's argument map. Null if the resolver takes no arguments.
 * @param converter Optional GRT converter for type-safe context creation
 * @param typeInfo Optional type information for the resolver's context types
 * @param objectValueClass The Java class for the parent object type (e.g., Person.class for a
 *        Person.fullAddress resolver). Used to convert engine object data to a typed Java instance
 *        when the resolver declares an objectValueFragment. Null if the resolver doesn't need
 *        object value access.
 */
class JavaFieldResolverExecutor(
    private val resolveFunction: (FieldExecutionContext<*, *, *, *>) -> CompletableFuture<*>,
    override val resolverId: String,
    private val resolverName: String,
    private val argumentsClass: Class<out Arguments>? = null,
    override val objectSelectionSet: RequiredSelectionSet? = null,
    override val querySelectionSet: RequiredSelectionSet? = null,
    private val objectValueClass: Class<*>? = null,
) : FieldResolverExecutor {
    override val metadata: ResolverMetadata = ResolverMetadata.forModern(resolverName, ResolverType.FIELD)
    override val isBatching: Boolean = false

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        // Unbatched resolver only handles single selector
        require(selectors.size == 1) {
            "Unbatched Java resolver should only receive single selector, got ${selectors.size}"
        }

        val selector = selectors.first()
        val result = runCatching {
            resolveOne(selector = selector, context = context)
        }

        return mapOf(selector to result)
    }

    private suspend fun resolveOne(
        selector: FieldResolverExecutor.Selector,
        context: EngineExecutionContext,
    ): Any? {
        val arguments = createArguments(selector.arguments)
        val objectValue = createObjectValue(selector)

        val javaContext = SimpleFieldExecutionContext(
            requestContext = context.requestContext,
            arguments = arguments,
            objectValue = objectValue,
        )

        // Call the Java resolver function and await the CompletableFuture
        val future = resolveFunction(javaContext)
        return future.await()
    }

    /**
     * Creates a typed Java object from the engine's object data using the syncObjectValueGetter.
     *
     * When a resolver declares an objectValueFragment (e.g., `@Resolver(objectValueFragment = "address { street city }")`),
     * the engine resolves those selections and provides them via the selector's syncObjectValueGetter.
     * This method converts that engine data into the typed Java object (e.g., Person) so the
     * resolver can access it via ctx.getObjectValue().
     *
     * Returns null if no objectValueClass is configured or no sync getter is available.
     */
    private suspend fun createObjectValue(selector: FieldResolverExecutor.Selector): Any? {
        if (objectValueClass == null) return null
        val syncGetter = selector.syncObjectValueGetter ?: return null
        val syncData = syncGetter()
        return convertEngineDataToJavaObject(objectValueClass, syncData)
    }

    /**
     * Converts an [EngineObjectData.Sync] into a Java object instance using reflection.
     *
     * Iterates over the available selections in the engine data and populates the Java object
     * via setter methods. Nested composite types (where the value is another [EngineObjectData.Sync])
     * are recursively converted to the setter's parameter type.
     */
    @Suppress("UNCHECKED_CAST")
    private fun convertEngineDataToJavaObject(
        clazz: Class<*>,
        data: EngineObjectData.Sync
    ): Any {
        val instance = clazz.getDeclaredConstructor().newInstance()
        for (selection in data.getSelections()) {
            val value = data.getOrNull(selection) ?: continue
            val setterName = "set${selection.replaceFirstChar { it.uppercase() }}"
            val setter = clazz.methods.firstOrNull { it.name == setterName && it.parameterCount == 1 }
                ?: continue
            val paramType = setter.parameterTypes[0]
            val convertedValue = when {
                value is EngineObjectData.Sync && !EngineObjectData::class.java.isAssignableFrom(paramType) ->
                    convertEngineDataToJavaObject(paramType, value)
                else -> value
            }
            setter.invoke(instance, convertedValue)
        }
        return instance
    }

    /**
     * Creates a typed Arguments instance from the engine's argument map using reflection.
     *
     * For each entry in the map, finds a matching setter on the Arguments class and invokes it.
     * Nested maps are recursively converted to the setter's expected parameter type.
     * Returns null if no arguments class is configured (resolver takes no arguments).
     */
    private fun createArguments(argumentMap: Map<String, Any?>): Arguments? {
        if (argumentsClass == null || argumentsClass == Arguments.None::class.java) {
            return null
        }

        return populateFromMap(argumentsClass, argumentMap) as Arguments
    }

    /**
     * Creates an instance of the given class and populates it from a map using setter reflection.
     *
     * Handles nested maps by recursively creating instances of the setter's parameter type.
     */
    @Suppress("UNCHECKED_CAST")
    private fun populateFromMap(
        clazz: Class<*>,
        map: Map<String, Any?>
    ): Any {
        val instance = clazz.getDeclaredConstructor().newInstance()
        for ((key, value) in map) {
            val setterName = "set${key.replaceFirstChar { it.uppercase() }}"
            val setter = clazz.methods.firstOrNull { it.name == setterName && it.parameterCount == 1 }
                ?: continue
            val paramType = setter.parameterTypes[0]
            val convertedValue = when {
                value == null -> null
                value is Map<*, *> && !Map::class.java.isAssignableFrom(paramType) ->
                    populateFromMap(paramType, value as Map<String, Any?>)
                else -> value
            }
            setter.invoke(instance, convertedValue)
        }
        return instance
    }
}
