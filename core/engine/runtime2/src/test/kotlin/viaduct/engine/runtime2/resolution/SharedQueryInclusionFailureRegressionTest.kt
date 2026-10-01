package viaduct.engine.runtime2.resolution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import viaduct.engine.runtime2.contract.selectionValues
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromQueryField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** A failed owner's guard must not suppress a different owner's included shared dependency. */
class SharedQueryInclusionFailureRegressionTest : ResolutionDispatcherResource {
    @Test
    fun `failed guard first preserves healthy owner's shared Query input`() = checkOrder("bad good")

    @Test
    fun `healthy guard first preserves healthy owner's shared Query input`() = checkOrder("good bad")

    @Test
    fun `failed Query path guard first preserves healthy owner input`() = checkOrder("bad good", true)

    @Test
    fun `healthy Query path guard first preserves healthy owner input`() = checkOrder("good bad", true)

    private fun checkOrder(
        order: String,
        fromQueryField: Boolean = false
    ) {
        val sourceApplications = AtomicInteger()
        val failure = IllegalStateException("bad owner's provider failed")
        val world = TestWorld.fromSDL(
            selectiveResolvers = true,
            schemaSDL = """
                type Query {
                  bad: Int!
                  good: Int!
                  source: Int!
                  badFlag: Boolean!
                  goodFlag: Boolean!
                }
            """.trimIndent(),
            fieldResolvers = { schema ->
                val owners = listOf("bad", "good").associate { name ->
                    val field = schema.loweredSchema.requireObjectField("Query", name)
                    val resolver = fieldResolverOf(
                        objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                        queryFragment = schema.fragmentFrom(
                            "fragment Owner on Query { " + (if (fromQueryField) "${name}Flag " else "") +
                                "source @include(if: ${'$'}enabled) }",
                            variableField = field,
                        ),
                    ) { _, queryValue, _ -> queryValue.selectionValues().getValue("source") }
                    field to if (fromQueryField) {
                        resolver
                    } else {
                        resolver.withVariablesProvider(setOf("enabled")) {
                            if (name == "bad") throw failure
                            mapOf("enabled" to true)
                        }
                    }
                }
                val flags = listOf("bad", "good").associate { name ->
                    schema.loweredSchema.requireObjectField("Query", "${name}Flag") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            if (name == "bad") EngineErrorData.of(failure) else true
                        }
                }
                owners + flags + (
                    schema.loweredSchema.requireObjectField("Query", "source") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            sourceApplications.incrementAndGet()
                            7
                        }
                )
            },
            variableProviders = { schema ->
                if (!fromQueryField) {
                    emptyMap()
                } else {
                    listOf("bad", "good").associate { name ->
                        val field = schema.loweredSchema.requireObjectField("Query", name)
                        Arguments.Variable.of(field, "enabled") to schema.fromQueryField(
                            queryFragmentSource = "fragment Flag on Query { ${name}Flag }",
                            responsePath = listOf("${name}Flag"),
                            variableField = field,
                        )
                    }
                }
            },
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
