package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.runtime.Engine1QueryExecutionContract
import viaduct.engine.runtime.tenantloading.RequiredSelectionsAreInvalid

class BasicFeatureExecutionTest : BasicFeatureExecutionContract, Engine1QueryExecutionContract {
    @Test
    @Disabled("Engine1 loops when fieldWithValue and a checker share the same field")
    override fun `resolver with checker test`() {
        super<BasicFeatureExecutionContract>.`resolver with checker test`()
    }

    @Test
    override fun `test invalid object fragment`() {
        val error = assertThrows<RequiredSelectionsAreInvalid> {
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
        assertTrue(error.message.orEmpty().contains("bar"), error.message.orEmpty())
    }

    @Test
    override fun `test invalid query fragment`() {
        val error = assertThrows<RequiredSelectionsAreInvalid> {
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
        assertTrue(error.message.orEmpty().contains("bar"), error.message.orEmpty())
    }
}
