package viaduct.engine.runtime2.execution

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.runtime.execution.FromFieldVariablesContract
import viaduct.engine.runtime.execution.fieldWithFromFieldVariables
import viaduct.engine.runtime.tenantloading.InvalidVariableException
import viaduct.engine.runtime.tenantloading.RequiredSelectionsCycleException
import viaduct.graphql.test.assertJson

class FromFieldVariablesContractTest : FromFieldVariablesContract, Engine2QueryExecutionContract {
    override fun invalidVariableName(error: Throwable): String = (error as InvalidVariableException).variableName

    override fun isRequiredSelectionsCycle(error: Throwable): Boolean = error is RequiredSelectionsCycleException

    override fun assertIncompatibleVariableError(
        error: Throwable,
        variableName: String,
        providerPath: String
    ) {
        val failure = assertInstanceOf(IllegalArgumentException::class.java, error)
        assertTrue(
            failure.message.orEmpty().contains(
                "Variable $variableName object provider path $providerPath is incompatible with one of its argument locations"
            ),
            failure.message.orEmpty(),
        )
    }

    override fun assertRequiredSelectionsCycleError(error: Throwable) {
        val failure = assertInstanceOf(IllegalArgumentException::class.java, error)
        assertTrue(
            failure.message.orEmpty().contains("Resolver object fragments contain a demand cycle"),
            failure.message.orEmpty(),
        )
    }

    @Disabled("TODO: Mutation")
    @Test
    fun `from object field -- simple mutation field`() {
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
            runQuery("mutation { x }").assertJson("{data: {x: 30}}")
        }
    }

    @Disabled("TODO: Mutation")
    @Test
    fun `from query field -- simple mutation field`() {
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
            runQuery("mutation {x}").assertJson("{data: {x: 30}}")
        }
    }
}
