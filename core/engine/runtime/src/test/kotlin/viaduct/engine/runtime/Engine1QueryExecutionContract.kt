package viaduct.engine.runtime

import graphql.ExecutionResult
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.execution.QueryExecutionContract
import viaduct.engine.runtime.execution.QueryRunner
import viaduct.graphql.test.assertJson

interface Engine1QueryExecutionContract : QueryExecutionContract {
    override fun executeQuery(
        module: EngineTestModule,
        query: String,
    ): ExecutionResult {
        lateinit var result: ExecutionResult
        module.runFeatureTest { result = runQuery(query) }
        return result
    }

    override fun assertQuery(
        module: EngineTestModule,
        query: String,
        expectedJson: String,
    ) {
        module.runFeatureTest {
            runQuery(query).assertJson(expectedJson)
        }
    }

    override fun assertQuery(
        module: EngineTestModule,
        query: String,
        expectedJson: String,
        withoutDefaultQueryNodeResolvers: Boolean,
    ) {
        module.runFeatureTest(withoutDefaultQueryNodeResolvers = withoutDefaultQueryNodeResolvers) {
            runQuery(query).assertJson(expectedJson)
        }
    }

    override fun assertQuery(
        module: EngineTestModule,
        schema: EngineSchema,
        query: String,
        expectedJson: String,
    ) {
        module.runFeatureTest(schema = schema) {
            runQuery(query).assertJson(expectedJson)
        }
    }

    override fun assertQueryWithTimeout(
        module: EngineTestModule,
        query: String,
        expectedJson: String,
    ) {
        module.runFeatureTest {
            runQueryWithin(query, timeoutMillis = 2_000).assertJson(expectedJson)
        }
    }

    override fun runQueryTest(
        module: EngineTestModule,
        withoutDefaultQueryNodeResolvers: Boolean,
        schema: EngineSchema?,
        engineConfig: EngineConfiguration?,
        block: (QueryRunner) -> Unit,
    ) {
        module.runFeatureTest(withoutDefaultQueryNodeResolvers, schema, engineConfig) {
            block(
                QueryRunner(
                    { query, variables -> runQuery(query, variables) },
                    { query, variables, timeoutMillis -> runQueryWithin(query, variables, timeoutMillis) },
                )
            )
        }
    }
}
