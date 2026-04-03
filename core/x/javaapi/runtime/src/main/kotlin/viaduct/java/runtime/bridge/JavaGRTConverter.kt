package viaduct.java.runtime.bridge

import graphql.schema.GraphQLSchema
import viaduct.engine.api.EngineObjectData
import viaduct.java.api.types.GraphQLObject

/**
 * Utility functions for converting between Java GRT (GraphQL Representational Type) objects
 * and the engine's [EngineObjectData] format.
 */

/**
 * Converts a Java resolver result to a form the engine can process.
 *
 * Java GRT objects (implementing [GraphQLObject]) are plain Java POJOs, not [EngineObjectData].
 * The engine requires [EngineObjectData] for composite types. This function converts Java GRTs
 * to [viaduct.engine.api.ResolvedEngineObjectData] by reflecting on their getters and looking
 * up the GraphQL type from the schema.
 *
 * Lists are converted element-by-element. Scalars and nulls are returned as-is.
 */
internal fun convertResult(
    result: Any?,
    graphqlSchema: GraphQLSchema?
): Any? {
    return when (result) {
        null -> null
        is GraphQLObject -> convertJavaGRTToEngineObjectData(result, graphqlSchema)
        is List<*> -> result.map { convertResult(it, graphqlSchema) }
        else -> result
    }
}

/**
 * Converts a Java GRT object (plain POJO implementing [GraphQLObject]) to
 * [viaduct.engine.api.ResolvedEngineObjectData].
 *
 * Uses the class simple name as the GraphQL type name, looks up the [graphql.schema.GraphQLObjectType]
 * from the schema, and populates field values by reflecting on getter methods.
 */
internal fun convertJavaGRTToEngineObjectData(
    grt: GraphQLObject,
    graphqlSchema: GraphQLSchema?
): viaduct.engine.api.EngineObjectData.Sync? {
    val schema = graphqlSchema ?: return null
    val typeName = grt.javaClass.simpleName
    val graphqlType = schema.getObjectType(typeName) ?: return null

    val data = mutableMapOf<String, Any?>()
    for (method in grt.javaClass.methods) {
        if (method.parameterCount != 0) continue
        if (method.declaringClass == Any::class.java) continue
        val fieldName = when {
            method.name.startsWith("get") && method.name.length > 3 ->
                method.name[3].lowercaseChar() + method.name.substring(4)
            method.name.startsWith("is") && method.name.length > 2 ->
                method.name[2].lowercaseChar() + method.name.substring(3)
            else -> continue
        }
        try {
            val value = method.invoke(grt)
            data[fieldName] = convertResult(value, graphqlSchema)
        } catch (_: Exception) {
            // Skip fields that cannot be read
        }
    }

    return viaduct.engine.api.ResolvedEngineObjectData(graphqlType, data)
}

/**
 * Converts an [EngineObjectData.Sync] into a Java object instance using reflection.
 *
 * Iterates over the available selections in the engine data and populates the Java object
 * via setter methods. Nested composite types (where the value is another [EngineObjectData.Sync])
 * are recursively converted to the setter's parameter type.
 */
@Suppress("UNCHECKED_CAST")
internal fun convertSyncEngineDataToJavaObject(
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
                convertSyncEngineDataToJavaObject(paramType, value)
            else -> value
        }
        setter.invoke(instance, convertedValue)
    }
    return instance
}

/**
 * Converts an [EngineObjectData] (async) into a Java object instance using reflection.
 *
 * Unlike [convertSyncEngineDataToJavaObject], this version awaits field values using the async
 * API ([EngineObjectData.fetchSelections] and [EngineObjectData.fetchOrNull]). Used for subquery
 * results returned by [viaduct.engine.api.EngineExecutionContext.resolveSelectionSet].
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun convertAsyncEngineDataToJavaObject(
    clazz: Class<*>,
    data: EngineObjectData
): Any {
    val instance = clazz.getDeclaredConstructor().newInstance()
    for (selection in data.fetchSelections()) {
        val value = data.fetchOrNull(selection) ?: continue
        val setterName = "set${selection.replaceFirstChar { it.uppercase() }}"
        val setter = clazz.methods.firstOrNull { it.name == setterName && it.parameterCount == 1 }
            ?: continue
        val paramType = setter.parameterTypes[0]
        val convertedValue = when {
            value is EngineObjectData && !EngineObjectData::class.java.isAssignableFrom(paramType) ->
                convertAsyncEngineDataToJavaObject(paramType, value)
            else -> value
        }
        setter.invoke(instance, convertedValue)
    }
    return instance
}
