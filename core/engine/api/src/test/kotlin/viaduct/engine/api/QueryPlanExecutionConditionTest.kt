package viaduct.engine.api

import graphql.schema.DataFetchingEnvironment
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.QueryPlanExecutionCondition

class QueryPlanExecutionConditionTest {
    @Nested
    inner class AlwaysExecuteTest {
        @Test
        fun `shouldExecute with env returns true`() {
            val env = mockk<DataFetchingEnvironment>(relaxed = true)

            val result = QueryPlanExecutionCondition.ALWAYS_EXECUTE.shouldExecute(env)

            assertTrue(result)
        }

        @Test
        fun `shouldExecute with null env returns true`() {
            val result = QueryPlanExecutionCondition.ALWAYS_EXECUTE.shouldExecute(null)

            assertTrue(result)
        }
    }

    @Nested
    inner class SamInterfaceTest {
        @Test
        fun `SAM interface works correctly with env`() {
            val conditionReturnsTrue = QueryPlanExecutionCondition { true }
            val conditionReturnsFalse = QueryPlanExecutionCondition { false }
            val env = mockk<DataFetchingEnvironment>(relaxed = true)

            assertTrue(conditionReturnsTrue.shouldExecute(env))
            assertFalse(conditionReturnsFalse.shouldExecute(env))
        }

        @Test
        fun `SAM interface works correctly with null env`() {
            val conditionReturnsTrue = QueryPlanExecutionCondition { true }
            val conditionReturnsFalse = QueryPlanExecutionCondition { false }

            assertTrue(conditionReturnsTrue.shouldExecute(null))
            assertFalse(conditionReturnsFalse.shouldExecute(null))
        }

        @Test
        fun `SAM interface receives env parameter`() {
            val env = mockk<DataFetchingEnvironment>(relaxed = true)
            var receivedEnv: DataFetchingEnvironment? = null
            val condition = QueryPlanExecutionCondition { e ->
                receivedEnv = e
                true
            }

            condition.shouldExecute(env)

            assertTrue(receivedEnv === env, "SAM should receive the env parameter")
        }
    }
}
