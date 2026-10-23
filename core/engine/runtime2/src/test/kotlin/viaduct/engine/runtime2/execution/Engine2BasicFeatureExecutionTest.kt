package viaduct.engine.runtime2.execution

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.runtime.execution.BasicFeatureExecutionContract

class Engine2BasicFeatureExecutionTest : BasicFeatureExecutionContract, Engine2QueryExecutionContract {
    @Test
    override fun `test invalid object fragment`() {
        val error = assertThrows<IllegalArgumentException> {
            val module = EngineTestModule("extend type Query { foo: Int }") {
                field("Query" to "foo") {
                    resolver {
                        objectSelections("bar")
                        fn { _, _, _, _, _ -> null }
                    }
                }
            }
            executeQuery(module, "{ foo }")
        }
        assertTrue(error.message.orEmpty().contains("Invalid GraphQL fragment"), error.message.orEmpty())
        assertTrue(error.message.orEmpty().contains("bar"), error.message.orEmpty())
    }

    @Test
    override fun `test invalid query fragment`() {
        val error = assertThrows<IllegalArgumentException> {
            val module = EngineTestModule("extend type Query { foo: Int }") {
                field("Query" to "foo") {
                    resolver {
                        querySelections("bar")
                        fn { _, _, _, _, _ -> null }
                    }
                }
            }
            executeQuery(module, "{ foo }")
        }
        assertTrue(error.message.orEmpty().contains("Invalid GraphQL fragment"), error.message.orEmpty())
        assertTrue(error.message.orEmpty().contains("bar"), error.message.orEmpty())
    }
}
