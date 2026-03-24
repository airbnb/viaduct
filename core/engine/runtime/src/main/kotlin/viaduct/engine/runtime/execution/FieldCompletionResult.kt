package viaduct.engine.runtime.execution

/**
 * Represents the result of a field completion in a GraphQL execution.
 * These values map to the value completion types defined in 6.4.3 in the spec (see: https://spec.graphql.org/draft/#sec-Value-Completion).
 */
sealed class FieldCompletionResult(val value: Any?, val isNullableField: Boolean) {
    class ScalarResult(scalarValue: Any?, isNullableField: Boolean) : FieldCompletionResult(scalarValue, isNullableField)

    class EnumResult(enumValue: String?, isNullableField: Boolean) : FieldCompletionResult(enumValue, isNullableField)

    class NullResult(isNullableField: Boolean) : FieldCompletionResult(null, isNullableField)

    class ListResult(listValue: Any?, val items: List<FieldCompletionResult>, isNullableField: Boolean) : FieldCompletionResult(listValue, isNullableField)

    class ObjectResult(objectValue: Map<String, Any?>, isNullableField: Boolean) : FieldCompletionResult(objectValue, isNullableField)

    companion object {
        fun scalar(
            value: Any?,
            executionParameters: ExecutionParameters
        ): ScalarResult = ScalarResult(value, isNullableField(executionParameters))

        fun enum(
            value: String?,
            executionParameters: ExecutionParameters
        ): EnumResult = EnumResult(value, isNullableField(executionParameters))

        fun nullValue(executionParameters: ExecutionParameters): NullResult = NullResult(isNullableField(executionParameters))

        fun list(
            value: Any?,
            items: List<FieldCompletionResult> = emptyList(),
            executionParameters: ExecutionParameters
        ): ListResult = ListResult(value, items, isNullableField(executionParameters))

        fun obj(
            value: Map<String, Any?>,
            executionParameters: ExecutionParameters
        ): ObjectResult = ObjectResult(value, isNullableField(executionParameters))

        private fun isNullableField(executionParameters: ExecutionParameters): Boolean {
            return !executionParameters.executionStepInfo.isNonNullType
        }
    }
}
