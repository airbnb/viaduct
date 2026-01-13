package viaduct.engine.runtime.execution

import com.airbnb.viaduct.errors.ViaductException
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLNamedType
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.TimeoutCancellationException
import viaduct.api.ViaductFrameworkException
import viaduct.api.ViaductTenantException
import viaduct.api.ViaductTenantResolverException
import viaduct.engine.runtime.exceptions.FieldFetchingException
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.utils.slf4j.logger

class ViaductDataFetcherExceptionHandler(val errorReporter: ErrorReporter, val errorBuilder: ResolverErrorBuilder) : DataFetcherExceptionHandler {
    companion object {
        // A map of error types to expose in the GraphQL error extensions
        // This is used to categorize the errors in the extensions for better error handling,
        // and overrides any error type that is already set in the exception.
        private val EXTENSION_ERROR_TYPES_OVERRIDE: Map<Class<*>, String> = mapOf(TimeoutCancellationException::class.java to "TIMEOUT")
        private val log by logger()
    }

    override fun handleException(handlerParameters: DataFetcherExceptionHandlerParameters): CompletableFuture<DataFetcherExceptionHandlerResult> {
        // Process the exception within this thread and return a completed future with the errors
        // Reporting to Upshot is still offloaded to another coroutine dispatcher and done asynchronously when reportException is invoked
        log.debug(
            "Handling exception for field: {}",
            handlerParameters.dataFetchingEnvironment.fieldDefinition.name,
            handlerParameters.exception
        )
        val errors = processException(handlerParameters)
        log.debug(
            "Processed exception for field: {} with error: {}",
            handlerParameters.dataFetchingEnvironment.fieldDefinition.name,
            errors
        )
        return CompletableFuture.completedFuture(
            DataFetcherExceptionHandlerResult.newResult().errors(errors).build()
        )
    }

    // A helper function that processes the exception and returns a list of GraphQLErrors
    private fun processException(params: DataFetcherExceptionHandlerParameters): List<GraphQLError> {
        // For metadata: unwrap ONLY concurrency wrappers, preserving the top-most Viaduct exception
        // (whether FieldFetchingException, ViaductTenantResolverException, etc.)
        val exceptionForMetadata = UnwrapExceptionUtil.unwrapException(params.exception, UnwrapExceptionUtil::isConcurrencyWrapper)

        // For errors: unwrap ALL wrappers (concurrency + Viaduct) to get the actual underlying error
        val unwrappedException = UnwrapExceptionUtil.unwrapExceptionForError(params.exception)

        val env = params.dataFetchingEnvironment
        val operationName: String? = env.operationDefinition.name
        val metadata = getMetadata(params, operationName, exceptionForMetadata)
        val errors = getErrors(unwrappedException, env, metadata)
        val message: String = getErrorMessage(operationName, env, metadata)

        errorReporter.reportResolverError(
            unwrappedException,
            message,
            metadata
        )

        return errors
    }

    /**
     * Builds a map of additional metadata we want to attach to the exception. This is
     * logged as part of the exception message.
     */
    private fun getMetadata(
        params: DataFetcherExceptionHandlerParameters,
        operationName: String?,
        exception: Throwable,
    ): ErrorReporter.Metadata {
        if (params.fieldDefinition == null) return ErrorReporter.Metadata.EMPTY
        val isFrameworkError = when (exception) {
            is ViaductFrameworkException -> true
            is ViaductTenantException -> false
            else -> null
        }

        val fieldName = params.fieldDefinition?.name
        val parentType = (params.dataFetchingEnvironment.parentType as? GraphQLNamedType)?.name

        @Suppress("DEPRECATION")
        return ErrorReporter.Metadata(
            fieldName = fieldName,
            parentType = parentType,
            operationName = operationName,
            isFrameworkError = isFrameworkError,
            resolvers = (exception as? ViaductTenantResolverException)?.let(::resolverCallChain),
            dataFetchingEnvironment = params.dataFetchingEnvironment
        )
    }

    private fun getErrors(
        exception: Throwable,
        env: DataFetchingEnvironment,
        metadata: ErrorReporter.Metadata
    ): List<GraphQLError> {
        val viaductErrors = errorBuilder.exceptionToGraphQLError(exception, metadata)

        val errors = viaductErrors?.map { viaductError ->
            val builder = GraphqlErrorBuilder.newError().message(viaductError.message)
            viaductError.path?.let { builder.path(it) } ?: builder.path(env.executionStepInfo.path)
            viaductError.locations?.let { locations ->
                builder.locations(locations.map { graphql.language.SourceLocation(it.line, it.column, it.sourceName) })
            }
            builder.extensions(viaductError.extensions ?: emptyMap())
            builder.build()
        }

        return errors
            ?: when (exception) {
                is FieldFetchingException -> listOf(exception.toGraphQLError())
                is ViaductException -> listOf(exception.toGraphQLError(env, metadata.toMap()))
                else ->
                    listOf(
                        GraphqlErrorBuilder.newError(env)
                            .message(exception.javaClass.name + ": " + exception.message)
                            .path(env.executionStepInfo.path)
                            .extensions(metadata.toMap())
                            .build()
                    )
            }
    }

    private fun getErrorMessage(
        operationName: String?,
        env: DataFetchingEnvironment,
        metadata: ErrorReporter.Metadata,
    ): String {
        return "Error fetching %s:%s of type %s.%s: %s".format(
            operationName,
            env.executionStepInfo.path,
            (env.parentType as? GraphQLNamedType)?.name,
            (env.fieldType as? GraphQLNamedType)?.name,
            metadata
        )
    }

    private fun resolverCallChain(exception: ViaductTenantResolverException): List<String> {
        return generateSequence(exception) { it.cause as? ViaductTenantResolverException }
            .map { it.resolver }
            .toList()
    }
}
