package viaduct.api.internal

import graphql.introspection.Introspection
import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.ViaductSchema
import viaduct.utils.string.decapitalize

/**
 * Internal factory for creating GraphQLInputObjectType instances for Arguments and Input GRTs.
 * This is framework-internal and should not be used by tenant code.
 */
@InternalApi
object InputTypeFactory {
    /**
     * Return a synthetic input type for an Argument GRT. "Synthetic" means the field
     * names and types conform to the argument names and types, but the returned input
     * type does _not_ exist in [schema].
     *
     * @param name Arguments GRT name (format: TypeName_FieldName_Arguments)
     * @param schema The Viaduct schema containing the field definition
     * @throws IllegalArgumentException if [name] isn't a valid Arguments GRT name
     */
    @JvmStatic
    fun argumentsInputType(
        name: String,
        schema: ViaductSchema
    ): GraphQLInputObjectType {
        val splitName = name.split("_")
        require(
            splitName.size == 3 &&
                splitName[0].isNotBlank() &&
                splitName[1].isNotBlank() &&
                splitName[2] == "Arguments"
        ) {
            "Invalid Arguments class name ($name). Expected format: TypeName_FieldName_Arguments"
        }
        val typeName = splitName[0]
        val type = requireNotNull(schema.schema.getType(typeName)) {
            "Type $typeName not in schema."
        }
        require(type is GraphQLObjectType) {
            "Type $type is not an object type."
        }
        val fieldName = splitName[1].decapitalize()
        val field = requireNotNull(type.getField(fieldName)) {
            "Field $typeName.$fieldName not found."
        }
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
        require(!fields.isEmpty()) {
            "No arguments found for field $typeName.$fieldName."
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
