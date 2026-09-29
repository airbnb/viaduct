@file:Suppress("ForbiddenImport")

package semantics.shared

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.InclusionCondition
import model.ResolverOccurrenceId
import model.VariableBinding
import model.requireObjectField
import model.testing.TestWorld
import model.testing.testRoot

class InclusionConditionTest {
    private val world = TestWorld.fromSDL("type Query { value: Boolean! }").assumptions
    private val field = world.schema.requireObjectField("Query", "value")

    @Test
    fun `condition evaluation short circuits without awaiting later alternatives`() =
        runBlocking {
            val operation = SharedOperationContext.create(world)
            val first = variable("first")
            val second = variable("second")
            operation.variableBindings.bindVariable(requireNotNull(first.instanceId), true)
            val condition =
                InclusionCondition.anyOf(
                    listOf(
                        InclusionCondition.requires(mapOf(first to true)),
                        InclusionCondition.requires(mapOf(second to true)),
                    ),
                )

            assertTrue(condition.fetchIncluded(operation))
        }

    @Test
    fun `failed condition binding is rejected`() {
        assertInvalidBinding(
            binding = VariableBinding.Error,
            expectedMessage = "Inclusion-condition variable failed",
        )
    }

    @Test
    fun `null condition binding is rejected`() {
        assertInvalidBinding(
            binding = VariableBinding.of(null),
            expectedMessage = "Inclusion-condition variable must contain a Boolean",
        )
    }

    @Test
    fun `non-boolean condition binding is rejected`() {
        assertInvalidBinding(
            binding = VariableBinding.of(1),
            expectedMessage = "Inclusion-condition variable must contain a Boolean",
        )
    }

    private fun assertInvalidBinding(
        binding: VariableBinding,
        expectedMessage: String,
    ) {
        val operation = SharedOperationContext.create(world)
        val variable = variable("condition")
        operation.variableBindings.bindVariable(requireNotNull(variable.instanceId), binding)
        val condition = InclusionCondition.requires(mapOf(variable to true))

        val failure =
            assertFailsWith<IllegalStateException> {
                runBlocking { condition.fetchIncluded(operation) }
            }

        assertTrue(failure.message.orEmpty().contains(expectedMessage))
    }

    private fun variable(name: String): Arguments.Variable =
        Arguments.Variable.of(field, name).instantiate(
            ResolverOccurrenceId.at(field.testRoot(), emptyList()),
        )
}
