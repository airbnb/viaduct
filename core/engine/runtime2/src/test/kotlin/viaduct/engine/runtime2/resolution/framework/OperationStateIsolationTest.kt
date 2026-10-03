@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution.framework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld

class OperationStateIsolationTest {
    private val world =
        TestWorld
            .fromSDL(
                "type Query { value(arg: Int!): Int! flag(arg: Boolean!): Int! }",
            ).assumptions
    private val field = world.schema.requireObjectField("Query", "value")
    private val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef())
    private val variable =
        Arguments.Variable
            .of(field, "arg")
            .instantiate(ResolverOccurrenceId.at(root, emptyList()))
    private val variableId = requireNotNull(variable.instanceId)
    private val symbolic =
        ObjectEngineResult.ObjectKey.of(
            field,
            mapOf("arg" to variable),
        )

    @Test
    fun `grounding and stored-key fallback use the supplied operation exclusively`() {
        val first = SharedOperationContext.create(world)
        val second = SharedOperationContext.create(world)
        val unbound = SharedOperationContext.create(world)
        first.variableBindings.bindVariable(variableId, 7)
        second.variableBindings.bindVariable(variableId, 9)
        val seven = ObjectEngineResult.GroundKey.of(field, mapOf("arg" to 7))
        val nine = ObjectEngineResult.GroundKey.of(field, mapOf("arg" to 9))
        val result = ObjectEngineResult.of(root.type, mapOf(seven to 70, nine to 90))

        repeat(2) {
            assertEquals(seven.arguments, symbolic.groundedArguments(first))
            assertEquals(nine.arguments, symbolic.groundedArguments(second))
            assertEquals(seven, result.findStoredKey(first, symbolic))
            assertEquals(nine, result.findStoredKey(second, symbolic))
        }
        assertFalse(symbolic.isContextuallyGrounded(unbound))
        assertNull(result.findStoredKey(unbound, symbolic))
        assertFailsWith<IllegalArgumentException> {
            symbolic.groundedArguments(unbound)
        }
    }

    @Test
    fun `symbolic identity wins over a grounded alias in each operation`() {
        val operation = SharedOperationContext.create(world)
        operation.variableBindings.bindVariable(variableId, 7)
        val grounded = ObjectEngineResult.GroundKey.of(field, mapOf("arg" to 7))
        val result = ObjectEngineResult.of(root.type, mapOf(symbolic to 70, grounded to 90))

        assertEquals(symbolic, result.findStoredKey(operation, symbolic))
        assertEquals(grounded, result.findStoredKey(operation, grounded))
        assertNull(result.findStoredKey(SharedOperationContext.create(world), symbolic))
    }

    @Test
    fun `suspended grounding cannot consume another operation's completed binding`(): Unit =
        runBlocking {
            val pending = SharedOperationContext.create(world)
            val completed = SharedOperationContext.create(world)
            pending.variableBindings.declareBinding(variableId)
            completed.variableBindings.bindVariable(variableId, 9)
            val waiting =
                async(start = CoroutineStart.UNDISPATCHED) {
                    symbolic.fetchGroundedArguments(pending)
                }

            try {
                assertFalse(waiting.isCompleted)
                assertEquals(
                    Arguments.Resolved.of(field, mapOf("arg" to 9)),
                    symbolic.fetchGroundedArguments(completed),
                )
                assertFalse(waiting.isCompleted)
                pending.variableBindings.completeBinding(variableId, 7)
                assertEquals(
                    Arguments.Resolved.of(field, mapOf("arg" to 7)),
                    withTimeout(5_000) { waiting.await() },
                )
            } finally {
                waiting.cancel()
            }
        }

    @Test
    fun `inclusion reads opposite bindings from the selected operation`(): Unit =
        runBlocking {
            val included = SharedOperationContext.create(world)
            val excluded = SharedOperationContext.create(world)
            val flag =
                Arguments.Variable
                    .of(world.schema.requireObjectField("Query", "flag"), "arg")
                    .instantiate(ResolverOccurrenceId.at(root, emptyList()))
            val flagId = requireNotNull(flag.instanceId)
            included.variableBindings.bindVariable(flagId, true)
            excluded.variableBindings.bindVariable(flagId, false)
            val condition = InclusionCondition.requires(mapOf(flag to true))

            repeat(2) {
                assertTrue(condition.isIncluded(included))
                assertFalse(condition.isIncluded(excluded))
                assertTrue(condition.fetchIncluded(included))
                assertFalse(condition.fetchIncluded(excluded))
            }
        }
}
