package viaduct.engine.runtime.context

import graphql.ExecutionInput
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategyParameters
import graphql.schema.DataFetchingEnvironment
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * `CompositeLocalContext` is meant to be used to store Viaduct-specific
 * context in various GraphQL-Java execution data structures, mainly
 * `ExecutionContext`, `ExecutionStrategyParameters`, and `DataFetchingEnvironment`.
 *
 * GraphQL-Java allows you to tuck an `Any` object into those data structures.
 * Historically we've created one great big Context class and have thrown into that
 * all the contextual needs of all of Viaduct, but this created a mess.
 * `CompositeLocalContext` is a container of a bunch of different context types
 * that are specific to different areas of Viaduct, giving us modularity and
 * encapsulation.  Inspired by Kotlin's `CoroutineContext`.
 */
class CompositeLocalContext private constructor(
    private val contexts: Map<KClass<*>, Any> = mapOf()
) {
    inline fun <reified T : Any> get() = get(T::class)

    fun <T : Any> get(ctxKlass: KClass<out T>): T? {
        @Suppress("UNCHECKED_CAST")
        return contexts[ctxKlass] as? T?
    }

    fun addOrUpdate(vararg newContext: Any): CompositeLocalContext {
        val newCtxs = contexts.toMutableMap()
        newContext.forEach { newCtxs[it::class] = it }
        return withContexts(newCtxs)
    }

    companion object {
        /** An empty immutable CompositeLocalContext */
        val empty: CompositeLocalContext = CompositeLocalContext()

        fun withContexts(vararg ctxs: Any) = withContexts(ctxs.toSet())

        fun withContexts(ctxs: Set<Any>) = withContexts(ctxs.associate { it::class to it })

        private fun withContexts(ctxs: Map<KClass<*>, Any>) = CompositeLocalContext(ctxs)
    }
}

// Public to support inline functions below
fun <T : Any> find(
    container: Any,
    ctx: Any?,
    ctxKlass: KClass<out T>
): T {
    if (ctx == null) throw NoSuchElementException("$container has no local context.")
    val compositeContext = ctx as? CompositeLocalContext
        ?: throw NoSuchElementException("Context of $container is not composite ($ctx).")
    val result = compositeContext.get(ctxKlass)
        ?: throw NoSuchElementException("No context of type ${ctxKlass.simpleName} in $container.")
    return ctxKlass.safeCast(result)
        ?: throw IllegalStateException("Incorrect type ($result) found for ${ctxKlass.simpleName} in $container.")
}

// ExecutionContext
private const val IS_INTROSPECTIVE = "viaduct.service.runtime.isIntrospective"

val ExecutionContext.isIntrospective: Boolean
    get() = graphQLContext.get<Boolean>(IS_INTROSPECTIVE) == true

fun ExecutionInput.setIsIntrospective(isIntrospective: Boolean) {
    graphQLContext.put(IS_INTROSPECTIVE, isIntrospective)
}

inline fun <reified T : Any> ExecutionContext.updateCompositeLocalContext(updater: (ctx: T?) -> T): CompositeLocalContext {
    val compositeContext =
        getLocalContext<CompositeLocalContext?>()
            ?: throw IllegalStateException(
                "Could not get CompositeLocalContext. Are you sure it's set at the root execution?"
            )
    val currentCtx = compositeContext.get<T>()
    val updatedCtx = updater(currentCtx)
    return compositeContext.addOrUpdate(updatedCtx)
}

inline fun <reified T : Any> ExecutionContext.getLocalContextForType(): T? {
    val compositeContext =
        getLocalContext<CompositeLocalContext?>()
            ?: return null
    return compositeContext.get<T>()
}

inline fun <reified T : Any> ExecutionContext.findLocalContextForType(): T = find(this, getLocalContext(), T::class)

inline fun <reified T : Any> ExecutionContext.hasLocalContextOfType(): Boolean = getLocalContextForType<T>() != null

// ExecutionInput

inline fun <reified T : Any> ExecutionInput.updateCompositeLocalContext(updater: (ctx: T?) -> T): CompositeLocalContext {
    val compositeContext = getLocalContext() as? CompositeLocalContext ?: CompositeLocalContext.empty
    val currentCtx = compositeContext.get<T>()
    val updatedCtx = updater(currentCtx)
    return compositeContext.addOrUpdate(updatedCtx)
}

inline fun <reified T : Any> ExecutionInput.getLocalContextForType(): T? {
    val compositeContext =
        localContext as? CompositeLocalContext
            ?: return null
    return compositeContext.get<T>()
}

inline fun <reified T : Any> ExecutionInput.findLocalContextForType(): T = find(this, localContext, T::class)

inline fun <reified T : Any> ExecutionInput.hasLocalContextOfType(): Boolean = getLocalContextForType<T>() != null

// DFE

inline fun <reified T : Any> DataFetchingEnvironment.getLocalContextForType(): T? {
    val compositeContext =
        getLocalContext<CompositeLocalContext?>()
            ?: return null
    return compositeContext.get<T>()
}

inline fun <reified T : Any> DataFetchingEnvironment.findLocalContextForType(): T = find(this, getLocalContext(), T::class)

inline fun <reified T : Any> DataFetchingEnvironment.hasLocalContextOfType(): Boolean = getLocalContextForType<T>() != null

// Execution Strategy Parameters

inline fun <reified T : Any> ExecutionStrategyParameters.updateCompositeLocalContext(updater: (ctx: T?) -> T): CompositeLocalContext {
    val compositeContext = getLocalContext() as? CompositeLocalContext ?: CompositeLocalContext.empty
    val currentCtx = compositeContext.get<T>()
    val updatedCtx = updater(currentCtx)
    return compositeContext.addOrUpdate(updatedCtx)
}

inline fun <reified T : Any> ExecutionStrategyParameters.getLocalContextForType(): T? {
    val compositeContext =
        localContext as? CompositeLocalContext
            ?: return null
    return compositeContext.get<T>()
}

inline fun <reified T : Any> ExecutionStrategyParameters.hasLocalContextOfType(): Boolean = getLocalContextForType<T>() != null
