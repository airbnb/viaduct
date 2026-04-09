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

    // NoCustomDirectivesRule
    const val CUSTOM_DIRECTIVE_NOT_ALLOWED = "CUSTOM_DIRECTIVE_NOT_ALLOWED"

    // ApplicationOnlyDefinitionsRule
    const val DIRECTIVE_DEFINED_IN_MODULE = "DIRECTIVE_DEFINED_IN_MODULE"
    const val SCALAR_DEFINED_IN_MODULE = "SCALAR_DEFINED_IN_MODULE"

    // BackingDataFieldsRule
    const val BACKING_DATA_MISSING_DIRECTIVE = "BACKING_DATA_MISSING_DIRECTIVE"
    const val BACKING_DATA_MISSING_TYPE = "BACKING_DATA_MISSING_TYPE"
    const val BACKING_DATA_ON_INPUT_FIELD = "BACKING_DATA_ON_INPUT_FIELD"

    // IdOfTypeValidationRule
    const val ID_OF_TYPE_NOT_FOUND = "ID_OF_TYPE_NOT_FOUND"
    const val ID_OF_TYPE_NOT_NODE = "ID_OF_TYPE_NOT_NODE"

    // NamespaceTypeConstraintsRule
    const val NAMESPACE_TYPE_FIELD_HAS_ARGS = "NAMESPACE_TYPE_FIELD_HAS_ARGS"
    const val NAMESPACE_TYPE_FIELD_IS_LIST = "NAMESPACE_TYPE_FIELD_IS_LIST"
    const val NAMESPACE_TYPE_FIELD_IS_NON_NULL = "NAMESPACE_TYPE_FIELD_IS_NON_NULL"
    const val NAMESPACE_TYPE_IN_UNION = "NAMESPACE_TYPE_IN_UNION"
    const val NAMESPACE_TYPE_MULTIPLE_PARENTS = "NAMESPACE_TYPE_MULTIPLE_PARENTS"
    const val NAMESPACE_TYPE_INVALID_PARENT = "NAMESPACE_TYPE_INVALID_PARENT"
}
