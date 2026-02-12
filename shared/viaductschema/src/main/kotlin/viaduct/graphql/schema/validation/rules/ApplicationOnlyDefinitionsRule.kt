package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates that directives and scalars are defined at application level only.
 *
 * Module partitions (schemas under partition/ directories) cannot define
 * their own directives or scalars - these must come from the application plugin.
 *
 * Definitions without a source location (e.g., built-in scalars) are automatically
 * skipped since they cannot originate from a module partition.
 *
 * @param modulePathPattern The path pattern that identifies module partition locations.
 */
class ApplicationOnlyDefinitionsRule(
    private val modulePathPattern: String
) : ValidationRule(
        id = "ApplicationOnlyDefinitions",
        description = "Directives and scalars must be defined at application level, not in modules"
    ) {
    override fun visitDirective(
        ctx: ValidationContext,
        directive: ViaductSchema.Directive
    ) {
        if (isModuleLocation(directive.sourceLocation)) {
            ctx.reportError(
                code = ValidationErrorCodes.DIRECTIVE_DEFINED_IN_MODULE,
                message = "Directive '@${directive.name}' cannot be defined in a module partition. " +
                    "Move directive definition to application-level schema.",
                location = SchemaLocation.ofDirective(directive.name).withSourceLocation(directive.sourceLocation)
            )
        }
    }

    override fun visitScalar(
        ctx: ValidationContext,
        scalar: ViaductSchema.Scalar
    ) {
        if (isModuleLocation(scalar.sourceLocation)) {
            ctx.reportError(
                code = ValidationErrorCodes.SCALAR_DEFINED_IN_MODULE,
                message = "Scalar '${scalar.name}' cannot be defined in a module partition. " +
                    "Move scalar definition to application-level schema.",
                location = SchemaLocation.ofType(scalar.name).withSourceLocation(scalar.sourceLocation)
            )
        }
    }

    /**
     * Checks if the source location indicates a module partition.
     *
     * Module schemas are located under partition/ directories, e.g.:
     * `central-schema/partition/moduleName/graphql/schema.graphql`
     *
     * This pattern comes from ViaductModulePlugin's module schema directory layout
     */
    private fun isModuleLocation(location: ViaductSchema.SourceLocation?): Boolean {
        return location?.sourceName?.contains(modulePathPattern) == true
    }
}
