package viaduct.engine

import graphql.ExecutionInput
import graphql.GraphQLError
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.language.OperationDefinition
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import viaduct.engine.runtime.context.setIsIntrospective

class IntrospectionRestrictingPreparsedDocumentProvider(
    private val wrappedProvider: PreparsedDocumentProvider
) : PreparsedDocumentProvider {
    override fun getDocumentAsync(
        executionInput: ExecutionInput,
        parseAndValidateFunction: Function<ExecutionInput, PreparsedDocumentEntry>
    ): CompletableFuture<PreparsedDocumentEntry?> =
        wrappedProvider.getDocumentAsync(executionInput, parseAndValidateFunction).thenApply { baseEntry ->
            val doc = baseEntry.document ?: return@thenApply baseEntry

            val inspection = InspectIntrospections.fromDocument(doc, executionInput.operationName)
            if (inspection?.hasIntrospection != true) {
                return@thenApply baseEntry
            }

            // Introspective queries handling
            executionInput.setIsIntrospective(true)

            when (val operationType = inspection.operationDefinition.operation) {
                OperationDefinition.Operation.MUTATION, OperationDefinition.Operation.SUBSCRIPTION -> {
                    val error = ValidationError.newValidationError()
                        .validationErrorType(ValidationErrorType.SubselectionNotAllowed)
                        .description("$operationType operations cannot introspect the schema.")
                        .build()
                    PreparsedDocumentEntry(baseEntry.document, baseEntry.errors + error)
                }

                OperationDefinition.Operation.QUERY -> {
                    if (!inspection.hasNonIntrospection) {
                        baseEntry
                    } else {
                        val error = ValidationError.newValidationError()
                            .validationErrorType(ValidationErrorType.SubselectionNotAllowed)
                            .description("Introspective queries cannot select non-introspective fields.")
                            .build()
                        PreparsedDocumentEntry(baseEntry.document, (baseEntry?.errors ?: emptyList<GraphQLError>()) + error)
                    }
                }
            }
        }
}
