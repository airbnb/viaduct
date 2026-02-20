package viaduct.engine.runtime

import graphql.execution.ResultPath
import graphql.schema.GraphQLObjectType
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.ACCESS_CHECK_SLOT
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT

fun objectEngineResult(init: ObjectEngineResultBuilder.() -> Unit): ObjectEngineResultImpl {
    val builder = ObjectEngineResultBuilder()
    builder.init()
    return builder.build()
}

/**
 * Helper function/classes for building ObjectEngineResults in tests
 */
class ObjectEngineResultBuilder {
    var type: GraphQLObjectType? = null
    var data: Map<String, Any?> = emptyMap()

    fun build(): ObjectEngineResultImpl {
        val oer = ObjectEngineResultImpl.newForType(type!!)
        data.forEach { (key, value) ->
            val oerKey = ObjectEngineResult.Key(key)
            oer.computeIfAbsent(oerKey) { setter ->
                val v = if (value is Throwable) {
                    Value.fromThrowable(value)
                } else {
                    Value.fromValue(value)
                }
                setter.set(RAW_VALUE_SLOT, v)
                setter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
            }
        }
        return oer
    }
}

suspend fun ObjectEngineResultImpl.dataAtPath(path: ResultPath): Any? {
    if (path == ResultPath.rootPath()) {
        return this
    }
    var current: Any? = this
    for (segment in path.toList()) {
        if (segment is String) {
            if (current is ObjectEngineResultImpl) {
                current = current.fetch(ObjectEngineResult.Key(segment), ObjectEngineResultImpl.RAW_VALUE_SLOT)
            } else {
                throw IllegalStateException(
                    "Invariant: expected $segment to be a key in an OER. Found $current instead."
                )
            }
        } else if (segment is Int) {
            if (current is List<*>) {
                current = (current[segment] as Cell).fetch(ObjectEngineResultImpl.RAW_VALUE_SLOT)
            } else {
                throw IllegalStateException(
                    "Invariant: expected $segment to point to an index in a list. Found $current instead."
                )
            }
        }
    }
    return current
}
