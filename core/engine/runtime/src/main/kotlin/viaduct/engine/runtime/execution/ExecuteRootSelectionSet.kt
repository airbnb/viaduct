@file:OptIn(ExperimentalTime::class)

package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.schema.GraphQLObjectType
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import viaduct.engine.runtime.EngineExecutionContextExtensions.incrementalExecutionEnabled
import viaduct.engine.runtime.execution.ViaductExecutionStrategy.Companion.buildExecutionResult
import viaduct.utils.slf4j.ifDebug
import viaduct.utils.slf4j.logger

/**
 * Executes the root selection set and returns its response, including any deferred work.
 * Implements Section 6.3.1, Executing the Root Selection Set.
 */
fun interface ExecuteRootSelectionSet {
    suspend operator fun invoke(
        parameters: ExecutionParameters,
        executionMode: ExecutionMode,
    ): ExecutionResult

    companion object {
        operator fun invoke(fieldResolver: FieldResolver): ExecuteRootSelectionSet = ExecuteRootSelectionSetImpl(fieldResolver)

        internal suspend fun buildExecutionResults(
            parameters: ExecutionParameters,
            fieldCompletionResult: Result<FieldCompletionResult>,
            errors: List<GraphQLError>,
        ): ExecutionResult {
            val result = buildExecutionResult(fieldCompletionResult, errors)
            val data = result.getData<Map<String, Any?>?>()
            val work = fieldCompletionResult.getOrNull()?.work ?: Work.empty
            val incrementalExecutionEnabled = parameters.engineExecutionContext.incrementalExecutionEnabled
            if (!incrementalExecutionEnabled) {
                check(work == Work.empty) { "Deferred work requires incremental execution" }
            }
            return if (data == null || !incrementalExecutionEnabled) {
                result
            } else {
                YieldIncrementalResults(parameters, data, errors, work)
            }
        }
    }
}

private class ExecuteRootSelectionSetImpl(
    private val fieldResolver: FieldResolver,
) : ExecuteRootSelectionSet {
    companion object {
        private val log by logger()
    }

    override suspend fun invoke(
        parameters: ExecutionParameters,
        executionMode: ExecutionMode,
    ): ExecutionResult {
        val objectType = parameters.executionStepInfo.type as GraphQLObjectType
        val operation = parameters.executionContext.operationDefinition

        CoroutineScope(currentCoroutineContext()).launch {
            supervisorScope {
                val (_, duration) = measureTimedValue {
                    fieldResolver.fetchObject(objectType, parameters, executionMode = executionMode).await()
                }
                log.ifDebug {
                    debug("Took $duration to resolve query: ${operation.name}.")
                }
            }
        }
        val (queryResult, duration) = measureTimedValue {
            runCatching {
                parameters.constants.fieldCompleter.completeObject(parameters).await()
            }
        }
        log.ifDebug {
            debug("Took $duration to complete query: ${operation.operation.name}.")
        }
        val (result, resultDuration) = measureTimedValue {
            ExecuteRootSelectionSet.buildExecutionResults(parameters, queryResult, parameters.errorAccumulator.toList())
        }
        log.ifDebug {
            debug("Took $resultDuration to build execution result stream: ${operation.operation.name}.")
        }
        return result
    }
}
