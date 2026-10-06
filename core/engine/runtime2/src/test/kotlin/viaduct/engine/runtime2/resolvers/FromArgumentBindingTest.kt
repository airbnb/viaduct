@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolvers

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
