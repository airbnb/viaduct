@file:Suppress("ForbiddenImport")

package semantics.resolvers

import model.requireField
import model.requireObjectField
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.requireQueryTypeDef
import kotlinx.coroutines.runBlocking
import model.VariableBinding
import model.emptyFragmentOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import semantics.shared.SharedOperationContext

class FromArgumentBindingTest {
    @Test
    fun `binding one resolver occurrence twice is rejected`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { echo(value: Int): Int }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireField("Query", "echo") to
                            fieldResolverOf(
                                schema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                0
                            },
                    )
                },
                variableProviders = { schema ->
                    val field = schema.requireObjectField("Query", "echo")
                    mapOf(
                        Arguments.Variable.of(field, "value") to
                            schema.fromArgument(field, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(world)
        val field = world.schema.requireObjectField("Query", "echo")
        val key = ObjectEngineResult.GroundKey.of(field, mapOf("value" to 1))
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), values = emptyMap())

        run {
            listOf(key).bindFromArguments(operation, root, emptyList())
            val variable =
                Arguments.Variable
                    .of(field, "value")
                    .instantiate(ResolverOccurrenceId.at(root, listOf(key)))
            val variableId = requireNotNull(variable.instanceId)
            assertEquals(
                VariableBinding.of(1),
                operation.variableBindings.getBinding(variableId),
            )
            assertEquals(
                VariableBinding.of(1),
                runBlocking { operation.variableBindings.fetchBinding(variableId) },
            )
            assertFailsWith<IllegalStateException> {
                listOf(key).bindFromArguments(operation, root, emptyList())
            }
        }
    }
}
