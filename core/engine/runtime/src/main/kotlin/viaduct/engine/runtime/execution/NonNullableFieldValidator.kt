package viaduct.engine.runtime.execution

import graphql.execution.NonNullableFieldWasNullError
import graphql.execution.NonNullableFieldWasNullException

/**
 * Validator for that non-nullable fields do not have a null value.
 *
 * This is forked from [graphql.execution.NonNullableFieldValidator]. The primary difference is that it records errors
 * in [ErrorAccumulator] rather than a list in the ExecutionContext. This ensures that nullability validation errors
 * from child QueryPlans do not leak into the execution result of the primary QueryPlan.
 */
object NonNullableFieldValidator {
    /**
     * Validate that [result] is not null when a non-null value is required.
     *
     * If validation fails, an error will be recorded in the provided [ErrorAccumulator]
     * and a [NonNullableFieldWasNullException] will be thrown if the error should be bubbled up.
     */
    fun validate(
        parameters: ExecutionParameters,
        result: Any?
    ) {
        if (result == null) {
            if (parameters.executionStepInfo.isNonNullType) {
                val nonNullException = NonNullableFieldWasNullException(parameters.executionStepInfo, parameters.path)
                parameters.errorAccumulator += NonNullableFieldWasNullError(nonNullException)
                if (parameters.executionContext.propagateErrorsOnNonNullContractFailure()) {
                    throw nonNullException
                }
            }
        }
    }
}
