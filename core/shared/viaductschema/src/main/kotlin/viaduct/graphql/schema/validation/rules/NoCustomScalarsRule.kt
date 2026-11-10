package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.SchemaProvenance
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.GraphQLBuiltIns
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

class NoCustomScalarsRule(
    private val builtInScalars: Set<String> = GraphQLBuiltIns.SCALARS
) : ValidationRule(
        id = "NoCustomScalars",
        description = "Only built-in GraphQL scalars are allowed"
    ) {
    private val builtInScalarNamesLower: Set<String> = builtInScalars.map { it.lowercase() }.toSet()

    override fun visitScalar(
        ctx: ValidationContext,
        scalar: ViaductSchema.Scalar
    ) {
        if (scalar.name.lowercase() !in builtInScalarNamesLower && !SchemaProvenance.isPluginScalar(scalar)) {
            ctx.reportError(
                code = ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED,
                message = "Custom scalar '${scalar.name}' is not allowed. " +
                    "Define plugin scalars in plugin-supplied SDL or use built-in scalars: ${builtInScalars.sorted().joinToString(", ")}",
                location = SchemaLocation.ofType(scalar.name).withSourceLocation(scalar.sourceLocation)
            )
        }
    }
}
