package viaduct.engine.runtime.execution

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.runtime.Engine1QueryExecutionContract
import viaduct.engine.runtime.tenantloading.InvalidVariableException
import viaduct.engine.runtime.tenantloading.RequiredSelectionsCycleException

class FromFieldVariablesContractTest : FromFieldVariablesContract, Engine1QueryExecutionContract {
    override fun invalidVariableName(error: Throwable): String = (error as InvalidVariableException).variableName

    override fun isRequiredSelectionsCycle(error: Throwable): Boolean = error is RequiredSelectionsCycleException

    @Test
    fun `from object field -- mutation object selections are rejected`() {
        val exception = assertThrows<Exception> {
            EngineTestModule(
                """
                    extend type Mutation { x:Int, y(b:Int):Int, z:Int }
                    extend type Query { empty:Int }
                """.trimIndent()
            ) {
                fieldWithFromFieldVariables(
                    coord = "Mutation" to "x",
                    objectSelectionsText = "y(b:\$b), z",
                    variables = listOf(FromObjectFieldVariable("b", "z")),
                ) { _, obj, _, _, _ ->
                    obj.fetchAs<Int>("y") * 5
                }
                field("Mutation" to "y") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                    }
                }
                fieldWithValue("Mutation" to "z", 2)
            }.runContractTest {
                runQuery("mutation { x }")
            }
        }
        assertTrue(exception.message!!.contains("Mutation.x must not declare object or query selections"))
    }

    @Test
    fun `from query field -- mutation query selections are rejected`() {
        val exception = assertThrows<Exception> {
            EngineTestModule(
                """
                extend type Mutation { x:Int, y(b:Int):Int }
                extend type Query { z:Int }
                """.trimIndent()
            ) {
                fieldWithFromFieldVariables(
                    coord = "Mutation" to "x",
                    objectSelectionsText = "y(b:\$z)",
                    querySelectionsText = "z",
                    variables = listOf(FromQueryFieldVariable("z", "z")),
                ) { _, obj, _, _, _ ->
                    obj.fetchAs<Int>("y") * 5
                }
                field("Mutation" to "y") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                    }
                }
                fieldWithValue("Query" to "z", 2)
            }.runContractTest {
                runQuery("mutation {x}")
            }
        }
        assertTrue(exception.message!!.contains("Mutation.x must not declare object or query selections"))
    }
}
