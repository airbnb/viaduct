package viaduct.engine.runtime2.resolution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import viaduct.engine.runtime2.bootstrap.fromQueryField
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** A failed owner's guard must not suppress a different owner's included shared dependency. */
class SharedQueryInclusionFailureRegressionTest : ResolutionDispatcherResource {
    @Test
    fun `failed guard first preserves healthy owner's shared Query input`() {
        checkOrder("bad good")
    }

    @Test
    fun `healthy guard first preserves healthy owner's shared Query input`() {
        checkOrder("good bad")
    }

    @Test
    fun `failed Query path guard first preserves healthy owner input`() {
        checkOrder("bad good", true)
    }

    @Test
    fun `healthy Query path guard first preserves healthy owner input`() {
        checkOrder("good bad", true)
    }

    private fun checkOrder(
        order: String,
        fromQueryField: Boolean = false
    ) {
        val sourceApplications = AtomicInteger()
        val failure = IllegalStateException("bad owner's provider failed")
        val ownerDeclarations = listOf("bad", "good").joinToString("\n") { name ->
            val binding = if (fromQueryField) {
                """variables: [{name: "enabled", fromQueryField: ["${name}Flag"]}]"""
            } else {
                val script = if (name == "bad") "throw 'bad owner provider failed'" else "{'enabled': true}"
                """vars: {names: ["enabled"], script: "$script"}"""
            }
            """
            $name: Int! @resolver(
              qf: "${if (fromQueryField) "${name}Flag " else ""}source @include(if: ${'$'}enabled)"
              $binding
              script: "queryValue.source"
            )
            """.trimIndent()
        }
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              $ownerDeclarations
              source: Int! @resolver(script: "sourceApplications.incrementAndGet(); 7")
              badFlag: Boolean! @resolver(script: "failure")
              goodFlag: Boolean! @resolver(script: "true")
            }
            """.trimIndent(),
            scriptBindings = mapOf(
                "sourceApplications" to sourceApplications,
                "failure" to EngineErrorData.of(failure),
            ),
        )
        val operation = SharedOperationContext.create(world.assumptions)
        val result = operation.resolveWithTestDispatcher(
            world.schemas.fragmentFrom("fragment Test on Query { $order }").subselections,
        )

        fun value(name: String) =
            result.getCell(
                ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", name), emptyMap()),
            ).value.get()

        assertIs<ErrorEngineResult>(value("bad"))
        assertEquals(7, value("good"), "Healthy owner must retain its included source for order: $order")
        assertEquals(1, sourceApplications.get(), "One healthy owner demands one source application")
    }
}
