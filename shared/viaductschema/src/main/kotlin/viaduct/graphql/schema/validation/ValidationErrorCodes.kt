package viaduct.graphql.schema.validation

/**
 * Standard error codes for schema validation rules.
 *
 * Using constants ensures consistency and enables IDE support for
 * code completion and refactoring.
 */
object ValidationErrorCodes {
    // NoSubscriptionsRule
    const val SUBSCRIPTION_NOT_ALLOWED = "SUBSCRIPTION_NOT_ALLOWED"

    // NoCustomScalarsRule
    const val CUSTOM_SCALAR_NOT_ALLOWED = "CUSTOM_SCALAR_NOT_ALLOWED"

    // ApplicationOnlyDefinitionsRule
    const val DIRECTIVE_DEFINED_IN_MODULE = "DIRECTIVE_DEFINED_IN_MODULE"
    const val SCALAR_DEFINED_IN_MODULE = "SCALAR_DEFINED_IN_MODULE"
}
