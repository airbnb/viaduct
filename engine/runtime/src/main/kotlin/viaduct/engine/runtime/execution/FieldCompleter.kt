@file:OptIn(ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")

package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.SimpleInstrumentationContext.nonNullCtx
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.deferred.asDeferred
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.TemporaryBypassAccessCheck
import viaduct.engine.runtime.Cell
import viaduct.engine.runtime.CompositeLocalContext
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.ACCESS_CHECK_SLOT
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.execution.CompletionErrors.FieldCompletionException
import viaduct.engine.runtime.execution.FieldExecutionHelpers.buildDataFetchingEnvironment
import viaduct.engine.runtime.execution.FieldExecutionHelpers.buildOERKeyForField
import viaduct.engine.runtime.execution.FieldExecutionHelpers.collectFields
import viaduct.engine.runtime.execution.FieldExecutionHelpers.executionStepInfoFactory

/**
 * Core component of Viaduct's execution engine responsible for completing GraphQL field values by transforming raw
 * resolved data into the final output format according to the GraphQL schema (https://spec.graphql.org/draft/#sec-Value-Completion).
 *
 * The FieldCompleter handles the final phase of GraphQL execution by:
 * 1. Converting raw field values into their proper GraphQL types
 * 2. Validating non-null constraints
 * 3. Coercing scalar and enum values
 * 4. Recursively completing nested objects and lists
 * 5. Handling errors and null values according to GraphQL specification
 *
 * Key responsibilities:
 * - Object completion: Processes all fields in a GraphQL object type
 * - Type-specific completion: Handles different GraphQL types (scalars, enums, lists, objects)
 * - Null validation: Enforces non-null constraints defined in the schema
 * - Error propagation: Properly bubbles up errors while maintaining partial results
 * - Instrumentation: Provides hooks for monitoring the completion process
 *
 * The completion process follows these rules:
 * - Null values are validated against non-null type constraints
 * - Lists are completed by recursively completing each item
 * - Scalars and enums are coerced to their proper representation
 * - Objects trigger recursive completion of their selected fields
 * - Errors during completion preserve partial results where possible
 *
 * ## Testing
 *
 * This component is tested via conformance and integration testing:
 *
 * - **Conformance tests** ([ArbitraryConformanceTest], [NullBubblingConformanceTest]) - 13,000+ property-based
 *   test iterations validating GraphQL spec compliance against the graphql-java reference implementation
 * - **Feature tests** ([ViaductExecutionStrategyTest], [ExceptionsTest]) - Targeted tests for field merging,
 *   error handling, and execution strategy integration
 * - **Engine feature tests** (EngineFeatureTest framework) - Integration tests exercising the complete
 *   resolution→completion pipeline with resolvers, checkers, and real schemas
 *
 * @see FieldResolver Pairs with this class to form the complete execution pipeline
 * @see FieldCompletionResult Contains the completed field values and metadata
 * @see ObjectEngineResultImpl Holds the intermediate execution results being completed
 * @see NonNullableFieldValidator Enforces schema non-null constraints
 * @see Conformer Test fixture for conformance testing
 */
class FieldCompleter(
    private val dataFetcherExceptionHandler: DataFetcherExceptionHandler,
    private val temporaryBypassAccessCheck: TemporaryBypassAccessCheck,
) {
    /**
     * Completes the selection set by completing each field.
     *
     * @param parameters The modern execution parameters.
     * @return A [Deferred] of [FieldCompletionResult] representing the completed fields.
     */
    fun completeObject(parameters: ExecutionParameters): Value<FieldCompletionResult> {
        val instrumentationParams = InstrumentationExecutionStrategyParameters(parameters.executionContext, parameters.gjParameters)
        val ctxCompleteObject = nonNullCtx(
            parameters.instrumentation.beginCompleteObject(instrumentationParams, parameters.executionContext.instrumentationState)
        )
        val parentOER = parameters.parentEngineResult
        return Value.fromDeferred(parentOER.state)
            .thenCompose { _, throwable ->
                ctxCompleteObject.onDispatched()
                if (throwable != null) {
                    ctxCompleteObject.onCompleted(null, throwable)
                    val field = checkNotNull(parameters.field)
                    val dataFetchingEnvironmentProvider = { buildDataFetchingEnvironment(parameters, field, parentOER) }
                    handleFetchingException(dataFetchingEnvironmentProvider, throwable)
                        .flatMap {
                            val err = FieldCompletionException(throwable, it.errors)
                            parameters.errorAccumulator += it.errors
                            Value.fromThrowable(err)
                        }
                } else {
                    objectFieldMap(parameters, parentOER).map { resolvedData ->
                        ctxCompleteObject.onCompleted(resolvedData, null)
                        FieldCompletionResult.obj(resolvedData, parameters)
                    }
                }
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun objectFieldMap(
        parameters: ExecutionParameters,
        parentOER: ObjectEngineResultImpl
    ): Value<Map<String, Any?>> {
        val fields = collectFields(parentOER.graphQLObjectType, parameters).selections
        val initial: Value<Map<String, Any?>> = Value.fromValue(emptyMap())

        return fields.fold(initial) { acc, field ->
            field as QueryPlan.CollectedField

            val newParams = parameters.forField(parentOER.graphQLObjectType, field)
            val fieldKey = buildOERKeyForField(newParams, field)
            val dataFetchingEnvironmentProvider = { buildDataFetchingEnvironment(newParams, field, parentOER) }

            val bypassChecker = temporaryBypassAccessCheck.shouldBypassCheck(field.mergedField.singleField, parameters.bypassChecksDuringCompletion)

            // Obtain a result for this field
            val combinedValue = combineValues(
                parentOER.getValue(fieldKey, RAW_VALUE_SLOT),
                parentOER.getValue(fieldKey, ACCESS_CHECK_SLOT),
                bypassChecker
            )

            val handledFetch = combinedValue.recover { throwable ->
                // Handle fetch errors gracefully
                handleFetchingException(dataFetchingEnvironmentProvider, throwable)
                    .map {
                        FieldResolutionResult(null, it.errors, CompositeLocalContext.empty, emptyMap(), null)
                    }
            }

            val singleField = completeField(field, newParams, handledFetch)

            // Now complete the field with the fetched result
            acc.flatMap { values ->
                singleField.map { values + (field.responseKey to it.value) }
            }
        }
    }

    /**
     * Combines the raw and access check values, bypassing the access check value if needed.
     * If the fetched raw value is exceptional, then discard the access check result and don't wait for it to complete.
     * If the raw value was successfully fetched, look for an access check error.
     */
    @Suppress("UNCHECKED_CAST")
    private fun combineValues(
        rawSlotValue: Value<*>,
        checkerSlotValue: Value<*>,
        bypassChecker: Boolean,
    ): Value<FieldResolutionResult> {
        val fieldResolutionResultValue = checkNotNull(rawSlotValue as? Value<FieldResolutionResult>) {
            "Expected raw slot to contain Value<FieldResolutionResult>, was ${rawSlotValue.javaClass}"
        }

        // Return raw value immediatley if bypassing check or checkerSlot value is null
        if (bypassChecker || checkerSlotValue == Value.nullValue) {
            return fieldResolutionResultValue
        }

        val checkerResultValue = checkNotNull(checkerSlotValue as? Value<out CheckerResult?>) {
            "Expected checker slot to contain Value<out CheckerResult>, was ${checkerSlotValue.javaClass}"
        }
        return fieldResolutionResultValue.flatMap {
            // At this point the raw value resolved successfully
            checkerResultValue.flatMap { checkerResult ->
                checkerResult?.asError?.error?.let { Value.fromThrowable(it) } ?: fieldResolutionResultValue
            }
        }
    }

    /**
     * Handles exceptions from data fetchers by delegating to the configured handler.
     *
     * @param dataFetchingEnvironmentProvider The environment provider
     * @param exception The exception to handle
     * @return [Value] of [DataFetcherExceptionHandlerResult] containing processed error information
     */
    private fun handleFetchingException(
        dataFetchingEnvironmentProvider: () -> DataFetchingEnvironment,
        exception: Throwable
    ): Value<DataFetcherExceptionHandlerResult> {
        val dfe = dataFetchingEnvironmentProvider()
        val dataFetcherExceptionHandlerParameters = DataFetcherExceptionHandlerParameters.newExceptionParameters()
            .dataFetchingEnvironment(dfe)
            .exception(exception)
            .build()

        return try {
            val fut = dataFetcherExceptionHandler.handleException(dataFetcherExceptionHandlerParameters)
            Value.fromDeferred(fut.asDeferred())
        } catch (e: Exception) {
            val simpleParams = DataFetcherExceptionHandlerParameters.newExceptionParameters()
                .dataFetchingEnvironment(dfe)
                .exception(e)
                .build()
            val fut = SimpleDataFetcherExceptionHandler().handleException(simpleParams)
            Value.fromDeferred(fut.asDeferred())
        }
    }

    /**
     * Completes a single field by completing its value.
     *
     * @param field The field to complete.
     * @param parameters The modern execution parameters.
     * @param fieldResolutionResult The result of fetching the field.
     * @return The [Value] of [FieldCompletionResult] for the completed field.
     */
    @Suppress("TooGenericExceptionCaught")
    fun completeField(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        fieldResolutionResult: Value<FieldResolutionResult>,
    ): Value<FieldCompletionResult> {
        val executionStepInfo = parameters.executionStepInfo
        val newParams = parameters.copy(
            executionStepInfo = executionStepInfo,
            parent = parameters,
        )

        val instParams = InstrumentationFieldCompleteParameters(
            parameters.executionContext,
            parameters.gjParameters,
            { executionStepInfo },
            fieldResolutionResult,
        )

        val fieldCompleteInstCtx = try {
            nonNullCtx(
                parameters.instrumentation.beginFieldCompletion(instParams, parameters.executionContext.instrumentationState)
            )
        } catch (e: Exception) {
            return getFieldCompletionResultForException(e)
        }

        return completeValue(field, newParams, fieldResolutionResult, fieldCompleteInstCtx)
            .thenCompose { completeResult, err ->
                try {
                    fieldCompleteInstCtx.onCompleted(completeResult?.value, err)
                    if (completeResult != null) {
                        Value.fromValue(completeResult)
                    } else {
                        Value.fromThrowable(checkNotNull(err))
                    }
                } catch (e: Exception) {
                    val exceptionError = FieldCompletionException(e, parameters)
                    parameters.errorAccumulator.addAll(exceptionError.graphQLErrors)
                    getFieldCompletionResultForException(exceptionError)
                }
            }
    }

    /**
     * Completes the value of a field based on its type.
     *
     * @param field The field whose value is to be completed.
     * @param parameters The modern execution parameters.
     * @param fieldResultValue the uncompleted field value
     * @return The [Value] of [FieldCompletionResult] representing the completed value.
     */
    private fun completeValue(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        fieldResultValue: Value<FieldResolutionResult>,
        fieldCompleteInstCtx: InstrumentationContext<Any>?,
    ): Value<FieldCompletionResult> =
        // A Value<FieldResolutionResult> can have a couple of different states:
        //   Throw - an exception was thrown somewhere during execution, perhaps
        //           by the resolver itself or an instrumentation
        //   Non-Throw, but with errors - no exceptions were thrown, however the resolver
        //           returned one or more errors. This can happen
        //   Non-Throw, without errors - no exceptions or graphql errors were generated.
        fieldResultValue.thenCompose { fieldResult, exception ->
            fieldCompleteInstCtx?.onDispatched()
            val unchecked =
                if (exception != null) {
                    // If an exception was thrown, wrap in a FieldCompletionException, add any
                    // errors to the error accumulator, and try to return null
                    val fce = FieldCompletionException(exception, parameters)
                    parameters.errorAccumulator.addAll(fce.graphQLErrors)
                    getFieldCompletionResultForException(fce)
                } else {
                    val (engineResult, errors) = checkNotNull(fieldResult)

                    // if the resolver completed non-exceptionally but returned errors, add them
                    // to the error accumulator
                    if (errors.isNotEmpty()) {
                        parameters.errorAccumulator.addAll(errors)
                    }

                    val result = parameters.executionContext.valueUnboxer.unbox(engineResult)
                    val fieldType = parameters.executionStepInfo.unwrappedNonNullType

                    when {
                        result == null -> completeValueForNull(parameters)

                        GraphQLTypeUtil.isList(fieldType) -> completeValueForList(field, parameters, result)
                        GraphQLTypeUtil.isScalar(fieldType) ->
                            completeValueForScalar(parameters, fieldType as GraphQLScalarType, result)

                        GraphQLTypeUtil.isEnum(fieldType) ->
                            completeValueForEnum(parameters, fieldType as GraphQLEnumType, result)

                        else -> {
                            completeValueForObject(field, parameters, fieldResult)
                        }
                    }
                }

            withNonNullValidation(parameters, unchecked)
        }

    /**
     * Completes a field value when it is null.
     *
     * @param parameters The modern execution parameters.
     * @return The [FieldCompletionResult] representing the null value.
     */
    private fun completeValueForNull(parameters: ExecutionParameters): Value<FieldCompletionResult> = Value.fromValue(FieldCompletionResult.nullValue(parameters))

    private fun getFieldCompletionResultForException(throwable: Throwable): Value<FieldCompletionResult> = Value.fromThrowable(throwable)

    private fun withNonNullValidation(
        parameters: ExecutionParameters,
        result: Value<FieldCompletionResult>
    ): Value<FieldCompletionResult> =
        result.thenCompose { completionResult, originalException ->
            // Early return for non-null types with existing exceptions to avoid duplicate error recording
            if (originalException != null && parameters.executionStepInfo.isNonNullType) {
                return@thenCompose Value.fromThrowable(originalException)
            }

            val fieldResult = completionResult ?: FieldCompletionResult.nullValue(parameters)

            val validationException = runCatching {
                NonNullableFieldValidator.validate(parameters, fieldResult.value)
                null // No exception means validation passed
            }.getOrElse { it }

            when {
                validationException == null -> Value.fromValue(fieldResult)
                fieldResult.isNullableField -> Value.fromValue(FieldCompletionResult.nullValue(parameters))
                else -> Value.fromThrowable(originalException ?: validationException)
            }
        }

    /**
     * Completes a field value when it is a list.
     *
     * @param field The field whose value is a list.
     * @param parameters The modern execution parameters.
     * @param result The result to complete.
     * @return The [Value] of [FieldCompletionResult] representing the list value.
     */
    private fun completeValueForList(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        result: Any
    ): Value<FieldCompletionResult> {
        @Suppress("UNCHECKED_CAST")
        val cells = checkNotNull(result as? Iterable<Cell>) {
            "Expected data to be an Iterable<Cell>, was ${result.javaClass}."
        }
        val bypassCheck = temporaryBypassAccessCheck.shouldBypassCheck(field.mergedField.singleField, parameters.bypassChecksDuringCompletion)
        val listValues = cells.map {
            combineValues(it.getValue(RAW_VALUE_SLOT), it.getValue(ACCESS_CHECK_SLOT), bypassCheck)
        }
        val instrumentationParams = InstrumentationFieldCompleteParameters(
            parameters.executionContext,
            parameters.gjParameters,
            { parameters.executionStepInfo },
            listValues
        )
        val completeListCtx = nonNullCtx(
            parameters.instrumentation.beginFieldListCompletion(
                instrumentationParams,
                parameters.executionContext.instrumentationState
            )
        )
        completeListCtx.onDispatched()

        // Start with a completed Value containing an empty list.
        val initial: Value<List<FieldCompletionResult>> = Value.fromValue(emptyList())
        val allItems = listValues.foldIndexed(initial) { i, acc, item ->
            val indexedPath = parameters.path.segment(i)
            val execStepInfoForItem =
                executionStepInfoFactory.newExecutionStepInfoForListElement(parameters.executionStepInfo, indexedPath)
            val newParams = parameters.copy(executionStepInfo = execStepInfoForItem)
            val completed = completeValue(field, newParams, item, null)
            acc.flatMap { values ->
                completed.map { value ->
                    values + value
                }
            }
        }

        // Once all items are collected into a List<FieldCompletionResult>, we transform them into a single FieldCompletionResult.
        return allItems
            .thenCompose { fieldValues, throwable ->
                if (throwable != null) {
                    completeListCtx.onCompleted(null, throwable)
                    getFieldCompletionResultForException(throwable)
                } else {
                    checkNotNull(fieldValues)
                    val listResults = fieldValues.map { it.value }
                    completeListCtx.onCompleted(listResults, null)
                    Value.fromValue(
                        FieldCompletionResult.list(
                            listResults,
                            fieldValues,
                            parameters,
                        )
                    )
                }
            }
    }

    /**
     * Completes a field value when it is a scalar.
     *
     * @param parameters The modern execution parameters.
     * @param scalarType The scalar type of the field.
     * @param result The result to complete.
     * @return The [FieldCompletionResult] representing the scalar value.
     */
    private fun completeValueForScalar(
        parameters: ExecutionParameters,
        scalarType: GraphQLScalarType,
        result: Any
    ): Value<FieldCompletionResult> {
        val serialized = try {
            scalarType.coercing.serialize(
                result,
                parameters.executionContext.graphQLContext,
                parameters.executionContext.locale
            )
        } catch (e: Exception) {
            val err = FieldCompletionException(e, parameters)
            parameters.errorAccumulator += err.graphQLErrors
            return Value.fromThrowable(err)
        }
        return Value.fromValue(
            FieldCompletionResult.scalar(serialized, parameters)
        )
    }

    /**
     * Completes a field value when it is an enum.
     *
     * @param parameters The modern execution parameters.
     * @param enumType The enum type of the field.
     * @param result The result to complete.
     * @return The [FieldCompletionResult] representing the enum value.
     */
    private fun completeValueForEnum(
        parameters: ExecutionParameters,
        enumType: GraphQLEnumType,
        result: Any?
    ): Value<FieldCompletionResult> {
        // Handle enum serialization with schema version skew tolerance
        // During hotswap/MTD, the runtime schema may have enum values that don't exist
        // in compiled tenant code. We validate against the runtime GraphQL schema
        // but avoid deserializing into Java enum types that may not have the value.
        val serialized = when (result) {
            null -> null
            is String -> {
                // String value - validate against runtime GraphQL schema
                if (enumType.getValue(result) != null) {
                    // Valid in runtime schema - use as-is without Java enum conversion
                    // This allows version skew: runtime schema may have values unknown to compiled code
                    result
                } else {
                    // Invalid in runtime schema - this is a real error
                    val err = FieldCompletionException(
                        IllegalArgumentException("Invalid enum value '$result' for type '${enumType.name}'"),
                        parameters
                    )
                    parameters.errorAccumulator += err.graphQLErrors
                    return Value.fromThrowable(err)
                }
            }
            is Enum<*> -> {
                // Java enum instance - extract name and validate
                val enumName = result.name
                if (enumType.getValue(enumName) != null) {
                    enumName
                } else {
                    // Enum instance doesn't exist in runtime schema
                    val err = FieldCompletionException(
                        IllegalArgumentException("Enum value '$enumName' not found in schema type '${enumType.name}'"),
                        parameters
                    )
                    parameters.errorAccumulator += err.graphQLErrors
                    return Value.fromThrowable(err)
                }
            }
            else -> {
                // Unexpected type - try GraphQL Java's serialize as fallback
                try {
                    enumType.serialize(
                        result,
                        parameters.executionContext.graphQLContext,
                        parameters.executionContext.locale
                    ) as String
                } catch (e: Exception) {
                    val err = FieldCompletionException(e, parameters)
                    parameters.errorAccumulator += err.graphQLErrors
                    return Value.fromThrowable(err)
                }
            }
        }

        return Value.fromValue(
            FieldCompletionResult.enum(serialized, parameters)
        )
    }

    /**
     * Completes a field value when it is an object.
     *
     * @param field The field whose value is an object.
     * @param parameters The modern execution parameters.
     * @param resolvedObjectType The resolved object type.
     * @param result The result to complete.
     * @return The [Value] of [FieldCompletionResult] representing the object value.
     */
    private fun completeValueForObject(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        result: FieldResolutionResult,
    ): Value<FieldCompletionResult> =
        completeObject(
            parameters.forObjectTraversal(
                field,
                result.engineResult as? ObjectEngineResultImpl
                    ?: throw IllegalStateException("Invariant: Expected ObjectEngineResultImpl for object completion"),
                result.localContext,
                result.originalSource
            )
        )
}
