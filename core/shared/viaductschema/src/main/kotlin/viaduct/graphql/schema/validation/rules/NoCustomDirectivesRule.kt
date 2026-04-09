package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.GraphQLBuiltIns
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates that schemas only use built-in GraphQL directives.
 *
 * Custom directives are not allowed in Viaduct schemas.
 *
 * @param builtInDirectives The set of directive names that are allowed. Defaults to the standard
 *                          GraphQL built-in directives: skip, include, deprecated, specifiedBy, oneOf.
 */
class NoCustomDirectivesRule(
    private val builtInDirectives: Set<String> = GraphQLBuiltIns.DIRECTIVES
) : ValidationRule(
        id = "NoCustomDirectives",
        description = "Only built-in GraphQL directives are allowed"
    ) {
    private val builtInDirectiveNamesLower: Set<String> = builtInDirectives.map { it.lowercase() }.toSet()

    override fun visitDirective(
        ctx: ValidationContext,
        directive: ViaductSchema.Directive
    ) {
        if (directive.name.lowercase() !in builtInDirectiveNamesLower) {
            ctx.reportError(
                code = ValidationErrorCodes.CUSTOM_DIRECTIVE_NOT_ALLOWED,
                message = "Custom directive '@${directive.name}' is not allowed. " +
                    "Use built-in directives: ${builtInDirectives.sorted().joinToString(", ") { "@$it" }}",
                location = SchemaLocation.ofDirective(directive.name).withSourceLocation(directive.sourceLocation)
            )
        }
    }
}
