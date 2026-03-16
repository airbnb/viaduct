package viaduct.api.internal

import graphql.introspection.Introspection
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.ViaductSchema

/**
 * Internal factory for creating GraphQLInputObjectType instances for Arguments and Input GRTs.
 * This is framework-internal and should not be used by tenant code.
 */
@InternalApi
object InputTypeFactory {
    /**
     * Return a synthetic input type for an Argument GRT using explicit type and field names.
     * "Synthetic" means the field names and types conform to the argument names and types,
     * but the returned input type does _not_ exist in [schema].
     *
     * @param name The Arguments class name (e.g. "Under_Score_Type_GetActivityListing_Arguments")
     * @param typeName The GraphQL type name (e.g. "Under_Score_Type")
     * @param fieldName The GraphQL field name (e.g. "getActivityListing")
     * @param schema The Viaduct schema containing the field definition
     * @throws IllegalArgumentException if the type/field is not found or has no arguments
     */
    @JvmStatic
    fun argumentsInputType(
        name: String,
        typeName: String,
        fieldName: String,
        schema: ViaductSchema
    ): GraphQLInputObjectType {
        val type = requireNotNull(schema.schema.getType(typeName)) {
            "Type $typeName not in schema."
        }
        require(type is GraphQLObjectType) {
            "Type $type is not an object type."
        }
        val field = requireNotNull(type.getField(fieldName)) {
            "Field $typeName.$fieldName not found."
        }
        return buildArgumentsInputType(name, field, schema)
    }

    private fun buildArgumentsInputType(
        name: String,
        field: GraphQLFieldDefinition,
        schema: ViaductSchema
    ): GraphQLInputObjectType {
        val fields = field.arguments.map {
            val builder = GraphQLInputObjectField.Builder()
                .name(it.name)
                .type(it.type)
                .replaceAppliedDirectives(
                    it.appliedDirectives.filter {
                        val def = schema.schema.getDirective(it.name)
                        Introspection.DirectiveLocation.INPUT_FIELD_DEFINITION in def.validLocations()
                    }
                )
            if (it.hasSetDefaultValue() && it.argumentDefaultValue.isLiteral) {
                val v = it.argumentDefaultValue.value as graphql.language.Value<*>
                builder.defaultValueLiteral(v)
            }
            builder.build()
        }
        require(fields.isNotEmpty()) {
            "No arguments found for field ${field.name} on type."
        }
        return GraphQLInputObjectType.Builder()
            .name(name)
            .fields(fields)
            .build()
    }

    /**
     * Return an input object type from the schema by name.
     *
     * @param name Input GRT name (must match a GraphQL input object type in schema)
     * @param schema The Viaduct schema
     * @throws IllegalArgumentException if [name] doesn't exist in schema
     */
    @JvmStatic
    fun inputObjectInputType(
        name: String,
        schema: ViaductSchema
    ): GraphQLInputObjectType {
        val result = requireNotNull(schema.schema.getType(name)) {
            "Type $name does not exist in schema."
        }
        return requireNotNull(result as? GraphQLInputObjectType) {
            "Type $name ($result) is not an input type."
        }
    }
}
