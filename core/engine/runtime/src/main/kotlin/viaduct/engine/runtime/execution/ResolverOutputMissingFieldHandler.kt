package viaduct.engine.runtime.execution

import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherResult
import graphql.schema.DataFetchingEnvironment
import viaduct.engine.runtime.observability.ResolverOutputContext
import viaduct.engine.runtime.observability.ResolverOutputMissingFieldException
import viaduct.service.api.spi.ErrorReporter
import viaduct.utils.slf4j.logger

/** Reports a field that is missing from resolver-produced output. */
object ResolverOutputMissingFieldHandler {
    @Suppress("DEPRECATION")
    fun reportMissingField(
        environment: DataFetchingEnvironment,
        objectType: String,
        fieldName: String,
        outputContext: ResolverOutputContext,
    ): DataFetcherResult<Any?>? {
        val exception = ResolverOutputMissingFieldException(
            objectType = objectType,
            fieldName = fieldName,
        )
        val message = checkNotNull(exception.message)
        try {
            outputContext.errorReporter.reportResolverError(
                exception = exception,
                errorMessage = message,
                metadata = ErrorReporter.Metadata(
                    fieldName = fieldName,
                    parentType = objectType,
                    isFrameworkError = false,
                    requestContext = environment.getContext<Any>(),
                ),
            )
        } catch (error: Exception) {
            log.warn(
                "Could not report missing resolver output field `{}.{}`",
                objectType,
                fieldName,
                error,
            )
        }
        return if (outputContext.missingFieldErrorsEnabled) {
            DataFetcherResult.newResult<Any?>()
                .error(
                    GraphqlErrorBuilder.newError(environment)
                        .message(message)
                        .extensions(
                            mapOf(
                                "code" to
                                    ResolverOutputMissingFieldException.GRAPHQL_ERROR_CODE
                            )
                        )
                        .build()
                )
                .build()
        } else {
            null
        }
    }

    private val log by logger()
}
