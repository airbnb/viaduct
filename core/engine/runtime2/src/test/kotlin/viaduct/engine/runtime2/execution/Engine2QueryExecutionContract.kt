package viaduct.engine.runtime2.execution

import graphql.ExecutionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.runtime.execution.QueryExecutionContract
import viaduct.engine.runtime.execution.QueryRunner
import viaduct.engine.runtime2.execution.testing.runQPlanFeatureTest
import viaduct.graphql.test.assertJson

interface Engine2QueryExecutionContract : QueryExecutionContract {
    @Test
    fun `alternates are paired with disabled tests`() {
        assertEquals(emptyList<String>(), alternatePairingViolations(collectAlternateMarks(this::class.java)))
    }

    override fun executeQuery(
        module: EngineTestModule,
        query: String,
    ): ExecutionResult {
        lateinit var result: ExecutionResult
        module.runQPlanFeatureTest { result = runQuery(query) }
        return result
    }

    override fun assertQuery(
        module: EngineTestModule,
        query: String,
        expectedJson: String,
    ) {
        module.runQPlanFeatureTest {
            runQuery(query).assertJson(expectedJson)
        }
    }

    override fun assertQuery(
        module: EngineTestModule,
        query: String,
        expectedJson: String,
        withoutDefaultQueryNodeResolvers: Boolean,
    ) {
        module.runQPlanFeatureTest(withoutDefaultQueryNodeResolvers = withoutDefaultQueryNodeResolvers) {
            runQuery(query).assertJson(expectedJson)
        }
    }

    override fun assertQuery(
        module: EngineTestModule,
        schema: EngineSchema,
        query: String,
        expectedJson: String,
    ) {
        module.runQPlanFeatureTest(schema = schema) {
            runQuery(query).assertJson(expectedJson)
        }
    }

    override fun assertQueryWithTimeout(
        module: EngineTestModule,
        query: String,
        expectedJson: String,
    ) {
        module.runQPlanFeatureTest {
            runQueryWithTimeout(query, timeoutMillis = 2_000).assertJson(expectedJson)
        }
    }

    override fun runQueryTest(
        module: EngineTestModule,
        withoutDefaultQueryNodeResolvers: Boolean,
        schema: EngineSchema?,
        engineConfig: EngineConfiguration?,
        block: (QueryRunner) -> Unit,
    ) {
        module.runQPlanFeatureTest(withoutDefaultQueryNodeResolvers, schema, engineConfig) {
            block(
                QueryRunner(
                    { query, variables -> runQuery(query, variables) },
                    { query, variables, timeoutMillis -> runQueryWithTimeout(query, variables, timeoutMillis) },
                )
            )
        }
    }
}
