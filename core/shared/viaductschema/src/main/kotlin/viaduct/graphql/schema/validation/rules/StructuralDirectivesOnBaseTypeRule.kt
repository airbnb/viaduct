package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates that structural directives (`@connection`, `@edge`, `@namespaceType`) are applied only to
 * the base type definition, not to `extend type` declarations.
 *
 * These directives define what a type *is* semantically — a connection container, an edge wrapper, or a
 * namespace grouping. Applying them on an extension is misleading: the base definition already exists
 * without the directive, so any code inspecting only the base extension will miss it.
 */
class StructuralDirectivesOnBaseTypeRule : ValidationRule(
    id = "StructuralDirectivesOnBaseType",
    description = "Structural directives (@connection, @edge, @namespaceType) must be on the base type definition, not on extend type"
) {
    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) {
        for (ext in obj.extensions) {
            if (ext.isBase) continue
            for (directive in STRUCTURAL_DIRECTIVES) {
                if (ext.hasAppliedDirective(directive)) {
                    ctx.reportError(
                        code = ValidationErrorCodes.STRUCTURAL_DIRECTIVE_ON_EXTENSION,
                        message = "@$directive on 'extend type ${obj.name}' is not allowed. " +
                            "@$directive must be placed on the base type definition of '${obj.name}'.",
                        location = SchemaLocation.ofType(obj.name).withSourceLocation(ext.sourceLocation)
                    )
                }
            }
        }
    }

    companion object {
        private val STRUCTURAL_DIRECTIVES = setOf("connection", "edge", "namespaceType")
    }
}
