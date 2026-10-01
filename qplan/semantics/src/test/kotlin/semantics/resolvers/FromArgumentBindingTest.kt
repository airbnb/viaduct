@file:Suppress("ForbiddenImport")

package semantics.resolvers

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.VariableBinding
import model.emptyFragmentOf
import model.registry.fieldResolverOf
import model.registry.fromArgument
import model.requireField
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import org.junit.jupiter.api.Test
import semantics.shared.SharedOperationContext

class FromArgumentBindingTest {
    @Test
    fun `binding one resolver occurrence twice is rejected`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = "type Query { echo(value: Int): Int }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireField("Query", "echo") to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                            ) { _, _ ->
                                0
                            },
                    )
                },
                variableProviders = { schema ->
                    val field = schema.loweredSchema.requireObjectField("Query", "echo")
                    mapOf(
                        Arguments.Variable.of(field, "value") to
                            schema.loweredSchema.fromArgument(field, "value"),
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
