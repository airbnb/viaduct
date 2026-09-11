package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.isNode
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule
import viaduct.graphql.utils.DefaultSchemaFactory.DefaultDirective

/**
 * Validates that type-level `@resolver` (as opposed to field-level `@resolver`) is only applied to
 * object types that implement `Node`.
 */
class NoTypeResolverOnNonNodeObjectsRule : ValidationRule(
    id = "NoTypeResolverOnNonNodeObjects",
    description = "Type-level @${DefaultDirective.RESOLVER.directiveName} can only be applied to types that implement Node"
) {
    override fun visitObject(
        ctx: ValidationContext,
        obj: ViaductSchema.Object
    ) {
        if (obj.isNode) return

        for (ext in obj.extensions) {
            if (!ext.hasAppliedDirective(DefaultDirective.RESOLVER.directiveName)) continue

            ctx.reportError(
                code = ValidationErrorCodes.RESOLVER_ON_NON_NODE_OBJECT,
                message = "Type ${obj.name} declares @${DefaultDirective.RESOLVER.directiveName} but does not implement Node. " +
                    "Add 'implements Node' with an 'id: ID!' field, or move " +
                    "@${DefaultDirective.RESOLVER.directiveName} to the fields it resolves.",
                location = SchemaLocation.ofType(obj.name).withSourceLocation(ext.sourceLocation)
            )
        }
    }
}
