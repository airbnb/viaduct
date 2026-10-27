package viaduct.engine.runtime2.execution

import graphql.ExecutionResult
import graphql.ExecutionResultImpl
import graphql.GraphQLError
import graphql.SerializationError
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.ExecutionStepInfoFactory
import graphql.execution.MergedField
import graphql.execution.NonNullableFieldWasNullError
import graphql.execution.NonNullableFieldWasNullException
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.language.Field
import graphql.schema.CoercingSerializeException
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import kotlinx.coroutines.future.await
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.QPlanEngineObjectData
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.schema.lowering.LOWERED_TYPENAME_FIELD

// Resolved checker inputs can bypass access checks; converting them must not start execution again.
internal suspend fun completeResolvedData(
    data: EngineObjectData.Sync,
    environment: DataFetchingEnvironment,
    handler: DataFetcherExceptionHandler,
): ExecutionResult {
    val completion = ResolvedDataCompletion(handler)
    val values = try {
        completion.objectValue(data, environment)
    } catch (_: ResolvedDataNullBubble) {
        null
    }
    return ExecutionResultImpl.newExecutionResult().data(values).errors(completion.errors).build()
}

private class ResolvedDataCompletion(private val handler: DataFetcherExceptionHandler) {
    val errors = mutableListOf<GraphQLError>()
    private val executionStepInfoFactory = ExecutionStepInfoFactory()

    suspend fun objectValue(
        data: EngineObjectData.Sync,
        environment: DataFetchingEnvironment
    ): Map<String, Any?> {
        val selections = data.getSelections().toList()
        val values = completeAll(selections) { selection ->
            val name = (data as? QPlanEngineObjectData)?.selectionFields?.get(selection)?.name ?: selection
            if (name == LOWERED_TYPENAME_FIELD || name == "__typename") {
                data.type.name
            } else {
                val field = requireNotNull(data.type.getFieldDefinition(name))
                val path = environment.executionStepInfo.path.segment(selection)
                val selectedField = MergedField.newMergedField(Field.newField(name).alias(selection.takeIf { it != name }).build()).build()
                val fieldEnvironment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(environment)
                    .source(data)
                    .parentType(data.type)
                    .fieldDefinition(field)
                    .fieldType(field.type)
                    .mergedField(selectedField)
                    .executionStepInfo(
                        environment.executionStepInfo.transform {
                            it.path(path).type(field.type).fieldDefinition(field).field(selectedField).fieldContainer(data.type)
                                .parentInfo(environment.executionStepInfo)
                        }
                    )
                    .build()
                value(data.outputValue(selection), field.type, fieldEnvironment)
            }
        }
        return selections.zip(values).toMap()
    }

    // Record every resolved sibling's errors before propagating a non-null failure to its parent.
    private suspend fun <T> completeAll(
        values: Iterable<T>,
        complete: suspend (T) -> Any?,
    ): List<Any?> {
        var failed = false
        val completed = values.map {
            try {
                complete(it)
            } catch (_: ResolvedDataNullBubble) {
                failed = true
                null
            }
        }
        if (failed) throw ResolvedDataNullBubble()
        return completed
    }

    private suspend fun value(
        data: Any?,
        type: GraphQLOutputType,
        environment: DataFetchingEnvironment
    ): Any? {
        if (type is GraphQLNonNull) {
            val errorCount = errors.size
            val completed = value(data, type.wrappedType as GraphQLOutputType, environment)
            if (completed == null) {
                if (errors.size == errorCount) {
                    errors += NonNullableFieldWasNullError(NonNullableFieldWasNullException(environment.executionStepInfo, environment.executionStepInfo.path))
                }
                throw ResolvedDataNullBubble()
            }
            return completed
        }
        return try {
            when {
                data is EngineErrorData -> {
                    val handled = try {
                        handler.handleException(
                            DataFetcherExceptionHandlerParameters.newExceptionParameters()
                                .dataFetchingEnvironment(environment)
                                .exception(data.cause ?: IllegalStateException("Resolved field failed"))
                                .build(),
                        )
                    } catch (failure: Exception) {
                        SimpleDataFetcherExceptionHandler().handleException(
                            DataFetcherExceptionHandlerParameters.newExceptionParameters()
                                .dataFetchingEnvironment(environment)
                                .exception(failure)
                                .build(),
                        )
                    }
                    errors += handled.await().errors
                    null
                }
                data == null -> null
                type is GraphQLList -> completeAll((data as List<*>).withIndex()) { (index, element) ->
                    val path = environment.executionStepInfo.path.segment(index)
                    val itemEnvironment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(environment)
                        .executionStepInfo(executionStepInfoFactory.newExecutionStepInfoForListElement(environment.executionStepInfo, path))
                        .build()
                    value(element, type.wrappedType as GraphQLOutputType, itemEnvironment)
                }
                type is GraphQLScalarType -> type.coercing.serialize(data, environment.graphQlContext, environment.locale)
                type is GraphQLEnumType -> type.serialize(data, environment.graphQlContext, environment.locale)
                data is EngineObjectData.Sync -> objectValue(data, environment)
                else -> error("Unexpected resolved value for $type")
            }
        } catch (failure: CoercingSerializeException) {
            errors += SerializationError(environment.executionStepInfo.path, failure)
            null
        } catch (_: ResolvedDataNullBubble) {
            null
        }
    }
}

private class ResolvedDataNullBubble : RuntimeException()
