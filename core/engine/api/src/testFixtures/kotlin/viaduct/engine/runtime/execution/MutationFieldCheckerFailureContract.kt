package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.EngineTestModule

interface MutationFieldCheckerFailureContract : QueryExecutionContract {
    @Test
    @DisplayName("mutation returning list with failed field checker handles result type correctly")
    fun mutationReturningListWithFailedFieldCheckerHandlesResultType() {
        EngineTestModule(
            """
            extend type Query { empty: Int }
            extend type Mutation { getUrls: [String!] }
            """.trimIndent()
        ) {
            fieldWithValue("Query" to "empty", null)
            field("Mutation" to "getUrls") {
                resolver {
                    fn { _, _, _, _, _ -> listOf("url1", "url2", "url3") }
                }
                checker {
                    fn { _, _ ->
                        throw IllegalAccessException("Privacy check failed: user not authorized")
                    }
                }
            }
        }.runContractTest {
            val result = runQuery("mutation { getUrls }")
            assertEquals(1, result.errors.size) { "Expected exactly one error from failed field check" }
            assertTrue(
                result.errors[0].message.contains("Privacy check failed") ||
                    result.errors[0].message.contains("not authorized"),
                "Expected error message to contain privacy failure info"
            )
            assertEquals(mapOf("getUrls" to null), result.getData<Map<String, Any?>>())
        }
    }
}
