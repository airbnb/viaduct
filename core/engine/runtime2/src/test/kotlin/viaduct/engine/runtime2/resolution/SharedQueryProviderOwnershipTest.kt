package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromObjectField
import viaduct.engine.runtime2.model.registry.fromQueryField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** Crossed providers retain absolute roots and equal-valued owners retain symbolic identities. */
class SharedQueryProviderOwnershipTest : ResolutionDispatcherResource {
    @Test
    fun `object and Query providers retain roots and owner identities inside one shared scope`() {
        val applications = ConcurrentHashMap<String, AtomicInteger>()

        fun count(name: String) {
            applications.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
        }

        val world =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      payload: Payload!
                      seed: Int!
                      consume(local: Int!, query: Int!): Int!
                    }
                    type Payload {
                      first: Int!
                      second: Int!
                      seed: Int!
                      localConsume(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val owners =
                        listOf("first", "second").associate { name ->
                            val field = schema.loweredSchema.requireObjectField("Payload", name)
                            field to
                                fieldResolverOf(
                                    objectFragment =
                                        schema.fragmentFrom(
                                            "fragment Local on Payload { seed localConsume(value: ${'$'}query) }",
                                            variableField = field,
                                        ),
                                    queryFragment =
                                        schema.fragmentFrom(
                                            "fragment QueryInput on Query { seed consume(local: ${'$'}local, query: ${'$'}query) }",
                                            variableField = field,
                                        ),
                                ) { input, query, _ ->
                                    count(name)
                                    assertEquals(
                                        setOf("seed", "localConsume"),
                                        input.getSelections().toSet(),
                                    )
                                    assertEquals(
                                        setOf("seed", "consume"),
                                        query.getSelections().toSet(),
                                    )
                                    assertEquals(11, input.outputValue("seed"))
                                    assertEquals(7, input.outputValue("localConsume"))
                                    assertEquals(7, query.outputValue("seed"))
                                    query.outputValue("consume")
                                }
                        }
                    owners +
                        mapOf(
                            schema.loweredSchema.requireObjectField("Query", "payload") to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                    schema.loweredSchema.objectOf("Payload") { "seed" setTo 11 }
                                },
                            schema.loweredSchema.requireObjectField("Query", "seed") to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                    count("querySeed")
                                    7
                                },
                            schema.loweredSchema.requireObjectField("Payload", "seed") to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Payload")) { _, _ ->
                                    error("Passive source owns this field")
                                },
                            schema.loweredSchema.requireObjectField("Payload", "localConsume") to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Payload")) { _, arguments ->
                                    count("localConsume")
                                    arguments.fieldValues.getValue("value")
                                },
                            schema.loweredSchema.requireObjectField("Query", "consume") to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, arguments ->
                                    count("queryConsume")
                                    (arguments.fieldValues.getValue("local") as Int) +
                                        (arguments.fieldValues.getValue("query") as Int)
                                },
                        )
                },
                variableProviders = { schema ->
                    listOf("first", "second")
                        .flatMap { name ->
                            val field = schema.loweredSchema.requireObjectField("Payload", name)
                            listOf(
                                Arguments.Variable.of(field, "local") to
                                    schema.fromObjectField(
                                        objectFragmentSource = "fragment Source on Payload { seed }",
                                        responsePath = listOf("seed"),
                                        variableField = field,
                                    ),
                                Arguments.Variable.of(field, "query") to
                                    schema.fromQueryField(
                                        queryFragmentSource = "fragment Source on Query { seed }",
                                        responsePath = listOf("seed"),
                                        variableField = field,
                                    ),
                            )
                        }.toMap()
                },
            )
        val observer = CorrectnessResolverObserver()
        val operation =
            SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val selections =
            world.schemas
                .fragmentFrom("fragment Test on Query { payload { first second } }")
                .subselections
        val result = operation.resolveWithTestDispatcher(selections)
        val payload =
            result
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Query", "payload"),
                        emptyMap(),
                    ),
                ).value
                .get() as ObjectEngineResult

        for (name in listOf("first", "second")) {
            val key =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Payload", name),
                    emptyMap(),
                )
            assertEquals(18, payload.getCell(key).value.get())
            assertEquals(1, applications[name]?.get())
        }
        assertEquals(1, applications["querySeed"]?.get())
        assertEquals(
            2,
            applications["queryConsume"]?.get(),
            "Equal values from distinct owner variables must remain separate symbolic cells",
        )
        assertEquals(2, applications["localConsume"]?.get())
        val query = observer.allQueryOERs().values.single { it.isDemanded() }.occurrence.target
        assertEquals(2, query.keys.count { it.field.name == "consume" })
        assertTrue(
            result.correctResolution(
                operation,
                selections.merge(world.schema.requireQueryTypeDef()),
            ),
        )
    }
}
