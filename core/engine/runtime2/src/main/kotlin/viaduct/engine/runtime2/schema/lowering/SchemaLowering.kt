package viaduct.engine.runtime2.schema.lowering

import graphql.schema.GraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.builder.ArgumentBuilder
import viaduct.graphql.schema.builder.ObjectTypeExtensionBuilder
import viaduct.graphql.schema.builder.OutputFieldBuilder
import viaduct.graphql.schema.builder.TypeExprBuilder
import viaduct.graphql.schema.builder.ViaductSchemaBuilder
import viaduct.graphql.schema.builder.ViaductSchemaBuilderFilter
import viaduct.graphql.schema.graphqljava.graphqlValidate
import viaduct.graphql.schema.graphqljava.viaductSchema
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationContext

/**
 * Creates qplan's field-resolution schema without modifying [source].
 */
internal fun lowerSchema(source: GraphQLSchema): ViaductSchema = lowerSchema(source.viaductSchema())

/**
 * Creates qplan's field-resolution schema without modifying [source].
 */
internal fun lowerSchema(source: ViaductSchema): ViaductSchema {
    val builder =
        ViaductSchemaBuilder.filteredCopy(
            source,
            object : ViaductSchemaBuilderFilter {},
        )
    val nodeType = source.types["Node"] as? ViaductSchema.Interface
    val queryType = source.types["Query"] as? ViaductSchema.Object
    if (nodeType != null && queryType?.field("node") == null) {
        builder.addDefinition(
            ObjectTypeExtensionBuilder("Query")
                .addField(
                    OutputFieldBuilder("node", TypeExprBuilder("Node"))
                        .addArgument(ArgumentBuilder("id", TypeExprBuilder("ID", nullable = false))),
                ),
        )
    }
    val context = SchemaLoweringContext(source, builder)
    val errors =
        SchemaValidator(
            phases =
                listOf(
                    listOf(
                        ReservedNamesRule(),
                        TypenameProxyRule(),
                    ),
                ),
        ).validate(context)
    require(errors.isEmpty()) {
        errors.joinToString(separator = "\n") { error ->
            "${error.message} (${error.location})"
        }
    }

    val lowered = builder.build()
    val graphQLErrors = graphqlValidate(lowered)
    require(graphQLErrors.isEmpty()) {
        graphQLErrors.joinToString(
            prefix = "Lowered schema is not GraphQL-valid:\n",
            separator = "\n",
        ) { it.message }
    }
    return lowered
}

internal class SchemaLoweringContext(
    source: ViaductSchema,
    val builder: ViaductSchemaBuilder,
) : ValidationContext(source)
