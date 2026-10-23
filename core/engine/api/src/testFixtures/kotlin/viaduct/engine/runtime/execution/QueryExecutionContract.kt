package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.EngineTestModule

interface QueryExecutionContract {
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
) {
    operator fun invoke(
        query: String,
        variables: Map<String, Any?> = emptyMap()
    ): ExecutionResult = execute(query, variables)

    fun withTimeout(
        query: String,
        variables: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 1_000,
    ): ExecutionResult = executeWithin(query, variables, timeoutMillis)
}
