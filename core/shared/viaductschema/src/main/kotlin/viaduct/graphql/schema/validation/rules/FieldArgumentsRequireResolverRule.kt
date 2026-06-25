package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule
import viaduct.graphql.utils.DefaultSchemaFactory.DefaultDirective

/**
 * Validates that object fields with arguments declare an explicit field resolver.
 */
class FieldArgumentsRequireResolverRule(
    private val resolverDirectiveName: String = DefaultDirective.RESOLVER.directiveName,
    private val namespaceTypeDirectiveName: String = DefaultDirective.NAMESPACE_TYPE.directiveName,
) : ValidationRule(
        id = "FieldArgumentsRequireResolver",
        description = "Object fields with arguments must have @resolver"
    ) {
    override fun visitField(
        ctx: ValidationContext,
        field: ViaductSchema.Field
    ) {
        val parentType = field.containingDef
        if (parentType !is ViaductSchema.Object) return
        if (!field.hasArgs) return
        if (field.hasAppliedDirective(resolverDirectiveName)) return
        if (field.type.baseTypeDef.hasAppliedDirective(namespaceTypeDirectiveName)) return

        ctx.reportError(
            code = ValidationErrorCodes.FIELD_WITH_ARGS_MISSING_RESOLVER,
            message = "Field ${parentType.name}.${field.name} has arguments but is missing @$resolverDirectiveName. " +
                "Object fields with arguments must declare @$resolverDirectiveName.",
            location = SchemaLocation.ofField(parentType.name, field.name).withSourceLocation(field.sourceLocation)
        )
    }
}
