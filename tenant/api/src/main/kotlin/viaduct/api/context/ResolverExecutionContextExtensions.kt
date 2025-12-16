@file:Suppress("UNCHECKED_CAST")

package viaduct.api.context

import java.util.concurrent.ConcurrentHashMap
import viaduct.api.globalid.GlobalID
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.apiannotations.StableApi

/**
 * Internal cache of NodeObject to avoid repeated reflection lookups.
 */
private object GeneratedTypesCache {
    private val cache = ConcurrentHashMap<Class<*>, Type<NodeObject>>()

    fun <T : NodeObject> get(clazz: Class<T>): Type<T> {
        val cached = cache[clazz]
        if (cached != null) return cached as Type<T>

        val reflectionClass = run {
            val direct = (clazz.declaredClasses + clazz.classes)
                .firstOrNull { it.simpleName == "Reflection" }
            direct ?: Class.forName("${clazz.name}\$Reflection")
        }

        val instanceField = reflectionClass.getField("INSTANCE")
        val instance = instanceField.get(null) as? Type<NodeObject>
            ?: error("Reflection.INSTANCE for ${clazz.name} is not a viaduct.api.reflect.Type")

        cache[clazz] = instance
        return instance as Type<T>
    }
}

/**
 * Wired-in extension to get the generated Type for a NodeObject subclass, using private cache object.
 */
@PublishedApi
internal fun <T : NodeObject> publishedGeneratedType(clazz: Class<T>): Type<T> = GeneratedTypesCache.get(clazz)

/**
 * Creates a Node object reference given a Local ID String representation.
 *
 * @see [viaduct.api.context.ResolverExecutionContext.nodeFor]
 */
@StableApi
inline fun <reified T : NodeObject> ResolverExecutionContext.nodeFor(localId: String): T {
    val type = publishedGeneratedType(T::class.java)
    val gid = globalIDFor(type, localId)
    return nodeFor(gid)
}

/**
 * Creates a GlobalID.
 *
 * @see [viaduct.api.context.ExecutionContext.globalIDFor]
 */
@StableApi
inline fun <reified T : NodeObject> ExecutionContext.globalIDFor(localId: String): GlobalID<T> {
    return globalIDFor(publishedGeneratedType(T::class.java), localId)
}
