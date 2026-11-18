@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.mocks.EngineTestModule

interface QueryExecutionContract {
    fun EngineTestModule.runContractTest(
        withoutDefaultQueryNodeResolvers: Boolean = false,
        schema: EngineSchema? = null,
        engineConfig: EngineConfiguration? = null,
        block: QueryRunner.() -> Unit,
    ) {
        runQueryTest(this, withoutDefaultQueryNodeResolvers, schema, engineConfig) { runner ->
            runner.block()
        }
    }

    fun executeQuery(
        module: EngineTestModule,
        query: String,
    ): ExecutionResult

    fun assertQuery(
        module: EngineTestModule,
        query: String,
        expectedJson: String,
    )

    fun assertQuery(
        module: EngineTestModule,
        query: String,
        expectedJson: String,
        withoutDefaultQueryNodeResolvers: Boolean,
    )

    fun assertQuery(
        module: EngineTestModule,
        schema: EngineSchema,
        query: String,
        expectedJson: String,
    )

    fun assertQueryWithTimeout(
        module: EngineTestModule,
        query: String,
        expectedJson: String,
    )

    fun runQueryTest(
        module: EngineTestModule,
        withoutDefaultQueryNodeResolvers: Boolean = false,
        schema: EngineSchema? = null,
        engineConfig: EngineConfiguration? = null,
        block: (QueryRunner) -> Unit,
    )
}

class QueryRunner(
    private val execute: (String, Map<String, Any?>) -> ExecutionResult,
    private val executeWithin: (String, Map<String, Any?>, Long) -> ExecutionResult,
    val engine: Engine,
) {
    fun runQuery(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionResult = invoke(query, variables)

    operator fun invoke(
        query: String,
        variables: Map<String, Any?> = emptyMap()
    ): ExecutionResult = execute(query, variables)

    fun withTimeout(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 1_000,
    ): ExecutionResult = executeWithin(query, variables, timeoutMillis)

    fun runIncremental(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 2_000,
        incrementalExecution: Boolean = true,
    ): ExecutionResult {
        val input = ExecutionInput(operationText = query, variables = variables, requestContext = Any())
        return runBlocking {
            withTimeout(timeoutMillis) {
                DefaultCoroutineInterop.enterThreadLocalCoroutineContext(coroutineContext) {
                    engine.execute(input, incrementalExecution = incrementalExecution)
                }.await()
            }
        }
    }
}
