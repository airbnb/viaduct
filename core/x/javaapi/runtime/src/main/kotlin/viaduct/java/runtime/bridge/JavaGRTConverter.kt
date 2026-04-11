package viaduct.java.runtime.bridge

import graphql.schema.GraphQLSchema
import org.slf4j.LoggerFactory
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.errors.FrameworkException
import viaduct.java.api.internal.JavaObjectBase
import viaduct.java.api.types.GraphQLObject

/**
 * Utility functions for converting between Java GRT (GraphQL Representational Type) objects
 * and the engine's [EngineObjectData] format.
 */

private val logger = LoggerFactory.getLogger("viaduct.java.runtime.bridge.JavaGRTConverter")

/**
 * Converts a Java resolver result to a form the engine can process.
 *
 * Java GRT objects (implementing [GraphQLObject]) now wrap [EngineObjectData.Sync] directly
 * (via [JavaObjectBase]). This function extracts the backing data without reflection.
 *
 * Lists are converted element-by-element. Scalars and nulls are returned as-is.
 */
internal fun convertResult(
    result: Any?,
    graphqlSchema: GraphQLSchema?
): Any? {
    return when (result) {
        null -> null
        is JavaObjectBase -> convertJavaGRTToEngineObjectData(result, graphqlSchema)
        is GraphQLObject -> throw FrameworkException(
            "Resolver returned a GraphQLObject that does not extend JavaObjectBase: ${result.javaClass.name}. " +
                "All Java GRT object types must extend JavaObjectBase."
        )
        is List<*> -> result.map { convertResult(it, graphqlSchema) }
        else -> result
    }
}

/**
 * Converts a [JavaObjectBase] GRT to [EngineObjectData.Sync].
 *
 * For engine-path GRTs (created from engine data), returns the backing [EngineObjectData.Sync] directly.
 * For builder-path GRTs (created via builder), converts the backing map to [ResolvedEngineObjectData].
 */
internal fun convertJavaGRTToEngineObjectData(
    grt: JavaObjectBase,
    graphqlSchema: GraphQLSchema?
): EngineObjectData.Sync? {
    // Engine-provided data: pass through directly (no copying needed)
    grt.engineObjectData?.let { return it }

    // Builder-created data: wrap map with proper GraphQL type from schema
    val map = grt.mapData ?: return null
    val schema = graphqlSchema ?: return null
    val typeName = grt.javaClass.simpleName
    val graphqlType = schema.getObjectType(typeName)
    if (graphqlType == null) {
        logger.warn(
            "Could not find GraphQL type '{}' in schema when converting builder-created GRT. " +
                "Ensure the Java class simple name matches the GraphQL type name.",
            typeName
        )
        return null
    }
    val convertedMap = map.mapValues { (_, v) -> convertValue(v, graphqlSchema) }
    return ResolvedEngineObjectData(graphqlType, convertedMap)
}

/** Recursively converts nested builder-created GRTs in a map value. */
private fun convertValue(
    value: Any?,
    schema: GraphQLSchema?
): Any? =
    when (value) {
        null -> null
        is JavaObjectBase -> convertJavaGRTToEngineObjectData(value, schema)
        is GraphQLObject -> throw FrameworkException(
            "Nested value is a GraphQLObject that does not extend JavaObjectBase: ${value.javaClass.name}. " +
                "All Java GRT object types must extend JavaObjectBase."
        )
        is List<*> -> value.map { convertValue(it, schema) }
        else -> value
    }

/**
 * Converts an [EngineObjectData.Sync] into a Java object instance using a single constructor call.
 *
 * The target class must have a public constructor accepting [EngineObjectData.Sync] (generated
 * by the new wrapping-based codegen). No reflection over fields or setters.
 */
internal fun convertSyncEngineDataToJavaObject(
    clazz: Class<*>,
    data: EngineObjectData.Sync
): Any {
    val constructor = clazz.getDeclaredConstructor(EngineObjectData.Sync::class.java)
    return constructor.newInstance(data)
}
