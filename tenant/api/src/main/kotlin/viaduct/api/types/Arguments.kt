package viaduct.api.types

import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLObjectType
import viaduct.engine.api.ViaductSchema
import viaduct.utils.string.decapitalize

/**
 * Tagging interface for virtual input types that wrap field arguments
 */
interface Arguments : InputLike {
    /** A marker object indicating the lack of schematic arguments */
    object NoArguments : Arguments

    companion object {
        /**
         * Return a syntehtic input type for an Argument GRT.  "Synthetic"
         * means the field names and types conform to the argument names
         * and types, but the returned input type does _not_ exist in
         * [schema].
         *
         * @throws IllegalArgumentException if [name] isn't a valid
         * Arguments GRT name for a field with arguments in [schema]
         */
        fun inputType(
            name: String,
            schema: ViaductSchema
        ): GraphQLInputObjectType {
            val splitName = name.split("_")
            require(splitName.size == 3 && splitName[2] == "Arguments") {
                "Invalid Arguments class name ($name)."
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
    }
}
