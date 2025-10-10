package com.airbnb.viaduct.errors

import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import graphql.validation.ValidationError
import java.io.Serializable

// Non-fatal exceptions should only be used internally to Viaduct framework not for tenant code.
// If you are adding a new exception class that's non-fatal, put it in the Internal class below to clearly show
// to the caller in order to prevent incidents from happening, e.g. CIM-8857.
// Or even better, put new non-fatal exceptions to a library that tenants don't have access to.

// WARNING: do not change the "value" property of any of these exceptions -- or add
// new ones -- without also modifying com.airbnb.viaduct.types.errors.ViaductErrorMessageText
sealed class ViaductErrorType(val value: String, val fatal: Boolean) {
    class DerivedFieldLoading(isFatal: Boolean) : ViaductErrorType(DerivedFieldLoading.value, isFatal) {
        companion object {
            val value = "DERIVED_FIELD_LOADING_ERROR"
        }
    }

    class SubQueryExecution(isFatal: Boolean) : ViaductErrorType(SubQueryExecution.value, isFatal) {
        companion object {
            val value = "SUB_QUERY_EXECUTION_ERROR"
        }
    }

    object FailedToPerformPolicyCheck : ViaductErrorType("FAILED_TO_PERFORM_POLICY_CHECK", true)

    object Internal : ViaductErrorType("INTERNAL_ERROR", true)

    object InternalConfiguration : ViaductErrorType("INTERNAL_CONFIGURATION_ERROR", true)

    object Mutation : ViaductErrorType("MUTATION_ERROR", true)

    object Validation : ViaductErrorType("VALIDATION", true)

    object NotFound : ViaductErrorType("NOT_FOUND", false)

    object Expired : ViaductErrorType("EXPIRED", false)

    object PermissionDenied : ViaductErrorType("PERMISSION_DENIED", false)

    object Sxs : ViaductErrorType("SXS_ERROR", false)

    object InternalRisk : ViaductErrorType("INTERNAL_RISK_ERROR", false)

    class ComponentLoading(isFatal: Boolean) : ViaductErrorType("COMPONENT_LOADING_ERROR", isFatal)

    open class Delegation(errorType: String = "DELEGATION_ERROR") : ViaductErrorType(errorType, true)

    object DelegationValidation : Delegation("DELEGATION_VALIDATION_ERROR")

    /**
     * An MutationInputValidation error is a validation error that is caused by the input provided to a mutation. This
     * is typically caused by a user error, and is not fatal. For example, if a user includes emoji characters in a
     * field that only accepts alphanumeric characters, this error would be thrown. It allows the client to display an
     * error without causing alerts to be sent to the on-call engineer.
     *
     * Note: for expected errors like bad user input it is recommended to use the result type pattern. This should only
     * be used for legacy mutation schema that doesn't implement that pattern.
     */
    object ClientInput : ViaductErrorType("CLIENT_INPUT", false)

    object NonNullError : ViaductErrorType("NON_NULL_ERROR", false)

    object FieldKillSwitch : ViaductErrorType("FIELD_KILL_SWITCH", true)
}

// LocalizedMessage field seems to be used by all FE frameworks (web, ios, android) that call us
// to find a user-friendly message in the exception thrown by viaduct to display in the UI.
// It's the responsiblity of the tenants to include these message that fits there use-cases but
// in case it's not specified, this is our way of providing a default one so FE UI can fail gracefully
val DEFAULT_LOCALIZED_MESSAGE_MAP =
    mapOf(
        "localizedMessage" to "Sorry, something went wrong. Please try again later."
    )

open class ViaductException(
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause) {
    fun toGraphQLError(
        dataFetchingEnvironment: DataFetchingEnvironment,
        additionalExtensions: Map<String, Serializable> = emptyMap()
    ): GraphQLError {
        return GraphqlErrorBuilder.newError(dataFetchingEnvironment)
            .message(getGraphQLMessage())
            .extensions(
                mapOf(
                    "errorType" to getErrorType().value,
                    "fatal" to getErrorType().fatal
                ) + DEFAULT_LOCALIZED_MESSAGE_MAP + getExtensions() + additionalExtensions
            )
            .build()
    }

    fun toGraphQLError(additionalExtensions: Map<String, String> = emptyMap()): GraphQLError {
        return GraphqlErrorBuilder.newError()
            .message(getGraphQLMessage())
            .extensions(
                mapOf(
                    "errorType" to getErrorType().value,
                    "fatal" to getErrorType().fatal
                ) + DEFAULT_LOCALIZED_MESSAGE_MAP + getExtensions() + additionalExtensions
            )
            .build()
    }

    protected open fun getGraphQLMessage(): String {
        return "${javaClass.name}:${message?.replace("%", "%%") ?: ""}"
    }

    open fun getErrorType(): ViaductErrorType = ViaductErrorType.Internal

    protected open fun getExtensions(): Map<String, Any?> = mapOf()

    companion object {
        const val ERROR_EXTENSIONS_KEY_POLICY_CHECK_FAILED_AT_PARENT_OBJECT = "failedAtParentObject"
    }
}

open class ViaductRequiredFieldValidationException(message: String, cause: Throwable? = null) :
    ViaductException(message, cause)

open class ViaductInternalExecutionException(message: String) : ViaductException(message)

class ViaductInvalidConfigurationException(message: String, cause: Throwable? = null) : ViaductException(message, cause) {
    override fun getErrorType() = ViaductErrorType.InternalConfiguration
}

class ViaductQueryValidationException(validationErrors: List<ValidationError>) : ViaductException(
    "Found GraphQL validation errors: %s".format(validationErrors)
) {
    override fun getErrorType() = ViaductErrorType.Validation
}

class ViaductQueryNormalizationException(message: String) : ViaductException(message) {
    override fun getErrorType() = ViaductErrorType.Validation
}

class ViaductFailedToPerformPolicyCheckException(
    message: String? = null,
    cause: Throwable? = null,
    private val failedAtParentObject: Boolean = false
) : ViaductException(message, cause) {
    override fun getGraphQLMessage(): String = message ?: "Failed to perform policy check"

    override fun getErrorType() = ViaductErrorType.FailedToPerformPolicyCheck

    override fun getExtensions(): Map<String, Any?> =
        mapOf(
            ERROR_EXTENSIONS_KEY_POLICY_CHECK_FAILED_AT_PARENT_OBJECT to failedAtParentObject
        )
}

open class ViaductMutationException(
    message: String? = null,
    cause: Throwable? = null
) : ViaductException(message, cause) {
    override fun getGraphQLMessage(): String = message ?: "Mutation error"

    override fun getErrorType() = ViaductErrorType.Mutation
}

/**
 * An ViaductClientInputException error is an exception that is caused by the input provided to a field. This is
 * typically caused by a user error, and is not fatal. For example, if a user includes emoji characters in a field that
 * only accepts alphanumeric characters, this exception would be thrown. It allows the client to display an error
 * without causing alerts to be sent to the on-call engineer.
 *
 * Note: For expected errors like bad user input it is recommended to use the result type pattern. This should only be
 * used for legacy mutation schema that doesn't implement that pattern.
 */
open class ViaductClientInputException(
    message: String? = null,
    cause: Throwable? = null
) : ViaductException(message, cause) {
    override fun getGraphQLMessage(): String = message ?: "Client input error"

    override fun getErrorType() = ViaductErrorType.ClientInput
}

class ViaductServiceBackedMutationException(
    message: String? = null,
    private val extensionMap: Map<String, Any?>?
) : ViaductMutationException(message, null) {
    override fun getExtensions(): Map<String, Any?> = extensionMap ?: mapOf()
}

class ViaductPsfGraphQLException(val graphqlErrors: List<GraphQLError>) :
    ViaductException(graphqlErrors.firstOrNull()?.message)

class ViaductDelegationValidationException(
    private val graphQLError: GraphQLError
) : ViaductDelegationException(listOf(graphQLError)) {
    override fun getErrorType() = ViaductErrorType.DelegationValidation
}

open class ViaductDelegationException(
    private val graphQLErrors: List<GraphQLError>
) : ViaductException(concatGraphQLErrors(graphQLErrors)) {
    override fun getErrorType() = ViaductErrorType.Delegation()

    override fun getGraphQLMessage(): String = "${javaClass.name}: ${concatGraphQLErrors(graphQLErrors)}"

    override fun getExtensions(): Map<String, Any?> = graphQLErrors.firstOrNull()?.extensions ?: mapOf()

    companion object {
        fun concatGraphQLErrors(graphQLErrors: List<GraphQLError>) = graphQLErrors.map { it.message }.joinToString()
    }
}

class ViaductPermissionDeniedException(
    message: String? = null,
    cause: Throwable? = null,
    private val failedAtParentObject: Boolean = false
) : ViaductException(message, cause) {
    override fun getGraphQLMessage(): String = message ?: "Permission denied"

    override fun getErrorType() = ViaductErrorType.PermissionDenied

    override fun getExtensions(): Map<String, Any?> {
        val extensions =
            mapOf(
                ERROR_EXTENSIONS_KEY_POLICY_CHECK_FAILED_AT_PARENT_OBJECT to failedAtParentObject
            )

        if (message != null) {
            return extensions + mapOf("localizedMessage" to message)
        }

        return extensions
    }
}

/**
 * Exception thrown when a GraphQL field is blocked by the kill switch.
 * FOR EMERGENCY USE ONLY.
 */
class ViaductFieldKillSwitchException(
    val typeName: String,
    val fieldName: String
) : ViaductException("Field at coordinate ($typeName, $fieldName) is blocked") {
    override fun getGraphQLMessage(): String = message ?: "Field blocked by kill switch"

    override fun getErrorType() = ViaductErrorType.FieldKillSwitch

    override fun getExtensions(): Map<String, Any?> =
        mapOf(
            "typeName" to typeName,
            "fieldName" to fieldName
        )
}
