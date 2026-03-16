package viaduct.graphql.schema.validation.rules

import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.schema.validation.ValidationRule

/**
 * Validates that the schema does not define a subscription type.
 *
 * Viaduct does not support GraphQL subscriptions, so schemas must not
 * define subscription root types.
 *
 * Errors are reported per field so that the source location points to the tenant
 * file that added the field (via `extend type Subscription`), not the framework's
 * placeholder in BUILTIN_SCHEMA. The framework adds a dummy `_` field to keep the
 * empty subscription type valid; those fields are ignored by this rule.
 */
class NoSubscriptionsRule : ValidationRule(
    id = "no-subscriptions",
    description = "Disallows subscription type definitions"
) {
    override fun visitSchema(ctx: ValidationContext) {
        val subscriptionType = ctx.schema.subscriptionTypeDef ?: return
        // The framework adds a dummy `_` field to the empty subscription root type to keep
        // it valid. Ignore those placeholder fields; only tenant-defined fields are violations.
        val tenantFields = subscriptionType.fields.filter { it.name != "_" }
        if (tenantFields.isEmpty()) return

        tenantFields.forEach { field ->
            ctx.reportError(
                code = ValidationErrorCodes.SUBSCRIPTION_NOT_ALLOWED,
                message = "Subscription type '${subscriptionType.name}' is not allowed. " +
                    "Viaduct does not support GraphQL subscriptions.",
                location = SchemaLocation.ofField(subscriptionType.name, field.name)
                    .withSourceLocation(field.sourceLocation)
            )
        }
    }
}
