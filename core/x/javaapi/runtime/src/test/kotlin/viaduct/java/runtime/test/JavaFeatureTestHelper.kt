package viaduct.java.runtime.test

import graphql.ExecutionResult
import java.util.function.Consumer
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.graphql.test.assertJson

/**
 * Java-friendly helper for running feature tests with the Viaduct engine.
 *
 * This class provides a simple static API that Java developers can use to test
 * their resolvers end-to-end through the Viaduct engine.
 *
 * Example usage from Java:
 * ```java
 * String schema = "extend type Query { greeting: String }";
 * TenantModuleBootstrapper bootstrapper = new SimpleJavaBootstrapper();
 *
 * JavaFeatureTestHelper.run(schema, bootstrapper, test -> {
 *     test.runQueryAndAssert("{ greeting }", "{data: {greeting: \"Hello, World!\"}}");
 * });
 * ```
 */
object JavaFeatureTestHelper {
    /**
     * Runs a feature test with the given schema and bootstrapper.
     *
     * @param schemaSDL the GraphQL schema definition
     * @param bootstrapper the bootstrapper that registers resolvers
     * @param testBlock the test logic to execute (receives a TestContext)
     */
    @JvmStatic
    fun run(
        schemaSDL: String,
        bootstrapper: TenantModuleBootstrapper,
        testBlock: Consumer<TestContext>
    ) {
        // Create a MockTenantModuleBootstrapper using DSL
        MockTenantModuleBootstrapper(schemaSDL) {
            // Register field resolvers from the bootstrapper
            val fieldResolvers = bootstrapper.fieldResolverExecutors(schema)
            for (entry in fieldResolvers) {
                val (typeName, fieldName) = entry.first
                val executor = entry.second
                field(typeName to fieldName) {
                    resolverExecutor { executor }
                }
            }
        }.runFeatureTest {
            testBlock.accept(TestContext(this))
        }
    }

    /**
     * Context passed to Java test blocks, providing query execution and assertion methods.
     */
    class TestContext internal constructor(
        private val featureTest: viaduct.engine.api.mocks.FeatureTest
    ) {
        /**
         * Runs a GraphQL query and returns the result.
         *
         * @param query the GraphQL query string
         * @return the execution result
         */
        fun runQuery(query: String): ExecutionResult {
            return featureTest.runQuery(query)
        }

        /**
         * Runs a GraphQL query with variables and returns the result.
         *
         * @param query the GraphQL query string
         * @param variables the query variables
         * @return the execution result
         */
        fun runQuery(
            query: String,
            variables: Map<String, Any?>
        ): ExecutionResult {
            return featureTest.runQuery(query, variables)
        }

        /**
         * Asserts that the execution result matches the expected JSON.
         * The expected JSON can use lenient syntax (unquoted keys, trailing commas, etc.)
         *
         * @param result the execution result to check
         * @param expectedJson the expected JSON
         */
        fun assertJson(
            result: ExecutionResult,
            expectedJson: String
        ) {
            result.assertJson(expectedJson)
        }

        /**
         * Runs a query and asserts the result in one call.
         *
         * @param query the GraphQL query string
         * @param expectedJson the expected JSON result
         * @return the execution result
         */
        fun runQueryAndAssert(
            query: String,
            expectedJson: String
        ): ExecutionResult {
            val result = runQuery(query)
            assertJson(result, expectedJson)
            return result
        }
    }
}
