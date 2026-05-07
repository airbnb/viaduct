package viaduct.engine.runtime.tenantloading

import graphql.schema.GraphQLObjectType
import org.slf4j.LoggerFactory
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry

private val log = LoggerFactory.getLogger("viaduct.engine.runtime.tenantloading.ExecutionRegistrySchemaValidator")

internal fun filterFieldsBySchema(
    fields: List<FieldEntry>,
    schema: ViaductSchema
): List<FieldEntry> =
    fields.filter { entry ->
        val rawType = schema.schema.getType(entry.typeName)
        val objectType = rawType as? GraphQLObjectType
        if (objectType == null) {
            if (rawType != null) {
                log.warn("Found resolver code for type {} which is not a GraphQL Object type.", entry.typeName)
            } else {
                log.warn("Found resolver code for {}.{}, but type {} is not defined in the schema.", entry.typeName, entry.fieldName, entry.typeName)
            }
            false
        } else {
            val fieldDef = objectType.getFieldDefinition(entry.fieldName)
            if (fieldDef == null) {
                log.warn("Found resolver code for {}.{}, but field {} is not defined on type {}.", entry.typeName, entry.fieldName, entry.fieldName, entry.typeName)
            }
            fieldDef != null
        }
    }

internal fun filterNodesBySchema(
    nodes: List<NodeEntry>,
    schema: ViaductSchema
): List<NodeEntry> =
    nodes.filter { entry ->
        val rawType = schema.schema.getType(entry.typeName)
        val nodeType = rawType as? GraphQLObjectType
        when {
            nodeType == null -> {
                if (rawType == null) {
                    log.warn("Found node resolver code for {} which is unknown in the schema.", entry.typeName)
                } else {
                    log.warn("Found resolver code for type {} which is not a GraphQL Object type.", entry.typeName)
                }
                false
            }
            nodeType.interfaces.none { it.name == "Node" } -> {
                log.warn("Found node resolver for {} which does not implement Node.", entry.typeName)
                false
            }
            else -> true
        }
    }
