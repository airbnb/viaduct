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
 */
class NoSubscriptionsRule : ValidationRule(
    id = "no-subscriptions",
    description = "Disallows subscription type definitions"
) {
    override fun visitSchema(ctx: ValidationContext) {
        val subscriptionType = ctx.schema.subscriptionTypeDef ?: return

        ctx.reportError(
            code = ValidationErrorCodes.SUBSCRIPTION_NOT_ALLOWED,
            message = "Subscription type '${subscriptionType.name}' is not allowed. " +
                "Viaduct does not support GraphQL subscriptions.",
            location = SchemaLocation.ofType(subscriptionType.name)
                .withSourceLocation(subscriptionType.sourceLocation)
        )
    }
}
