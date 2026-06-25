package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule
import viaduct.graphql.utils.DefaultSchemaFactory.DefaultDirective

/**
 * Validates that interface fields do not declare field resolvers.
 *
 * Viaduct field resolvers are generated and registered against concrete object type coordinates.
 * Interface fields can be selected by clients, but resolver implementations must live on the
 * concrete object fields that implement the interface.
 */
class NoResolverOnInterfaceFieldsRule : ValidationRule(
    id = "NoResolverOnInterfaceFields",
    description = "Interface fields cannot declare @${DefaultDirective.RESOLVER.directiveName}"
) {
    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {
        val containingDef = field.containingDef
        if (containingDef !is ViaductSchema.Interface) return
        if (!field.hasAppliedDirective(DefaultDirective.RESOLVER.directiveName)) return

        ctx.reportError(
            code = ValidationErrorCodes.RESOLVER_ON_INTERFACE_FIELD,
            message = "Field ${containingDef.name}.${field.name} is declared on an interface and cannot use " +
                "@${DefaultDirective.RESOLVER.directiveName}. Declare resolvers on concrete object fields instead.",
            location = SchemaLocation.ofField(containingDef.name, field.name).withSourceLocation(field.sourceLocation)
        )
    }
}
