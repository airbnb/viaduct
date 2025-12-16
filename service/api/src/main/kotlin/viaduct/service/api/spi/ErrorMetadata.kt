package viaduct.service.api.spi

/**
 * Metadata about a resolver error including execution context.
 *
 * This class encapsulates various details about the error, such as the field name, parent type,
 * operation name, whether it is a framework error, the resolvers involved, and the error type.
 */
data class ErrorMetadata(
    /**
     * The name of the field where the error occurred.
     */
    val fieldName: String? = null,
    /**
     * The type of the parent object where the error occurred.
     */
    val parentType: String? = null,
    /**
     * The name of the operation where the error occurred.
     */
    val operationName: String? = null,
    /**
     * Indicates whether the error is a framework error or caused by a tenant.
     */
    val isFrameworkError: Boolean? = null,
    /**
     * The list of resolvers involved in the error, represented as a string of the class name.
     *
     * Example: "MyCustomTypeResolver"
     */
    val resolvers: List<String>? = null,
    /**
     * The type of the error, if available.
     */
    val errorType: String? = null,
    /**
     * The execution path to the field where the error occurred.
     */
    val executionPath: List<Any>? = null,
    /**
     * Source location in the GraphQL query document where the field was requested.
     */
    val sourceLocation: SourceLocation? = null,
    /**
     * The source object being resolved (the parent object).
     */
    val source: Any? = null,
    /**
     * The GraphQL context containing request-level data.
     */
    val context: Any? = null,
    /**
     * The local context for field-specific data.
     */
    val localContext: Any? = null,
    /**
     * The component name associated with the field definition.
     */
    val componentName: String? = null
) {
    /**
     * Converts ErrorMetadata to a map for backward compatibility.
     * Used by existing code that passes errorMetadata.toMap().
     */
    fun toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        fieldName?.let { map["fieldName"] = it }
        parentType?.let { map["parentType"] = it }
        operationName?.let { map["operationName"] = it }
        isFrameworkError?.let { map["isFrameworkError"] = it.toString() }
        resolvers?.let { map["resolvers"] = it.joinToString(" > ") }
        errorType?.let { map["errorType"] = it }
        return map
    }

    override fun toString(): String {
        return listOfNotNull(fieldName, parentType, operationName, isFrameworkError, resolvers, errorType)
            .joinToString(separator = ", ", prefix = "{", postfix = "}")
    }

    companion object {
        val EMPTY = ErrorMetadata(
            fieldName = null,
            parentType = null,
            operationName = null,
            isFrameworkError = null,
            resolvers = null,
            errorType = null,
            executionPath = null,
            sourceLocation = null,
            source = null,
            context = null,
            localContext = null,
            componentName = null
        )
    }
}
