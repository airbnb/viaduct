package viaduct.engine.runtime2.contract

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.registry.selectionAwareFieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation

/** Object- and Query-fragment coverage for variables supplied by a tenant provider function. */
interface VariablesProviderResolverContract : QueryFragmentVariablesResolverContract {
    @Test
    fun `successor demand propagates symbolic arguments through nested paths and preserves known null`() {
        for ((knownNull, providedValue) in listOf(false to 4, false to null, true to null)) {
            val providerCalls = AtomicInteger()
            val applications = ResolverApplicationArguments()
            val payload = providedValue?.let { mapOf("value" to it) }
            val testWorld = TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL = """
                    type Query { item: Item }
                    input Envelope { nested: Payload }
                    input Payload { value: Int }
                    type Item {
                        result: Int
                        middle: Leaf
                        relay(input: Envelope, stamp: Int!): Int
                        seed(input: Payload, stamp: Int!): Int
                        consume(value: Int): Int
                        backing: Int
                    }
                    type Leaf { value: Int }
                """.trimIndent(),
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    val backing = schema.requireObjectField("Item", "backing")
                    mapOf(
                        schema.requireObjectField("Query", "item") to selectionAwareFieldResolverOf(
                            objectFragment = schema.emptyFragmentOf("Query"),
                            queryFragment = schema.emptyFragmentOf("Query"),
                        ) { _, _, _, demand ->
                            val keys = demand.merge(backing.containingDef).keys().toList()
                            schema.objectOf("Item") {
                                if (!selectiveResolvers || keys.any { it.field == backing }) "backing" setTo 3
                            }
                        },
                        schema.requireObjectField("Item", "result") to fieldResolverOf(
                            schemas.fragmentFrom("fragment Input on Item { middle { value } }"),
                        ) { input, _ ->
                            (input.selectionValues().getValue("middle") as EngineObjectData.Sync).selectionValues().getValue("value")
                        },
                        schema.requireObjectField("Item", "middle") to fieldResolverOf(
                            schemas.fragmentFrom(
                                "fragment Input on Item { relay(input: {nested: ${if (knownNull) "null" else "${'$'}provided"}}, stamp: ${'$'}stamp) }",
                            ),
                        ) { input, _ -> schema.objectOf("Leaf") { "value" setTo input.selectionValues().getValue("relay") } }
                            .withVariablesProvider(if (knownNull) setOf("stamp") else setOf("provided", "stamp")) {
                                providerCalls.incrementAndGet()
                                if (knownNull) mapOf("stamp" to 9) else mapOf("provided" to payload, "stamp" to 9)
                            },
                        schema.requireObjectField("Item", "relay") to fieldResolverOf(
                            schemas.fragmentFrom("fragment Input on Item { seed(input: ${'$'}nested, stamp: ${'$'}stamp) }"),
                        ) { input, _ -> input.selectionValues().getValue("seed") },
                        schema.requireObjectField("Item", "seed") to fieldResolverOf(
                            schemas.fragmentFrom("fragment Input on Item { consume(value: ${'$'}value) }"),
                        ) { input, _ -> input.selectionValues().getValue("consume") },
                        schema.requireObjectField("Item", "consume") to fieldResolverOf(
                            schemas.fragmentFrom("fragment Input on Item { backing }"),
                        ) { input, arguments ->
                            (arguments.fieldValues.getValue("value") as Int? ?: 10) + (input.selectionValues().getValue("backing") as Int)
                        },
                    )
                },
                variableProviders = { schemas ->
                    val schema = schemas.loweredSchema
                    val relay = schema.requireObjectField("Item", "relay")
                    val seed = schema.requireObjectField("Item", "seed")
                    mapOf(
                        Arguments.Variable.of(relay, "nested") to schema.fromArgument(relay, listOf("input", "nested")),
                        Arguments.Variable.of(relay, "stamp") to schema.fromArgument(relay, "stamp"),
                        Arguments.Variable.of(seed, "value") to schema.fromArgument(seed, listOf("input", "value")),
                    )
                },
            )
            val result = resolveAndValidate(testWorld, "query { item { result } }", resolverObserver = applications)
            val schema = testWorld.schema
            val item = assertIs<ObjectEngineResult>(result.getCell(schema.contractKey("Query", "item")).get())

            assertEquals((providedValue ?: 10) + 3, item.getCell(schema.contractKey("Item", "result")).get())
            assertEquals(1, providerCalls.get())
            applications.assertApplications(
                mapOf(
                    schema.requireObjectField("Query", "item") to listOf(emptyMap()),
                    schema.requireObjectField("Item", "result") to listOf(emptyMap()),
                    schema.requireObjectField("Item", "middle") to listOf(emptyMap()),
                    schema.requireObjectField("Item", "relay") to listOf(mapOf("input" to mapOf("nested" to payload), "stamp" to 9)),
                    schema.requireObjectField("Item", "seed") to listOf(mapOf("input" to payload, "stamp" to 9)),
                    schema.requireObjectField("Item", "consume") to listOf(mapOf("value" to providedValue)),
                ),
            )
        }
    }

    @Test
    fun `nested object fragment provider is bound only for an active resolver occurrence`() {
        for ((supplied, providerFails) in listOf(false to false, true to false, false to true, true to true)) {
            val providerCalls = AtomicInteger()
            val applications = ConcurrentLinkedQueue<String>()
            val observer = object : CorrectnessResolverObserver() {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    super.onResolverInvocation(observation)
                    applications += "${observation.field.containingDef.name}/${observation.field.name}"
                }
            }
            val testWorld = TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL = """
                    type Query { item: Item }
                    type Item { result: Int middle: Leaf seed(value: Int): Int backing: Int }
                    type Leaf { value: Int }
                """.trimIndent(),
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(
                        schema.requireObjectField("Query", "item") to selectionAwareFieldResolverOf(
                            objectFragment = schema.emptyFragmentOf("Query"),
                            queryFragment = schema.emptyFragmentOf("Query"),
                        ) { _, _, _, demand ->
                            schema.objectOf("Item") {
                                if (supplied) "middle" setTo schema.objectOf("Leaf") { "value" setTo 9 }
                                if (!selectiveResolvers || demand.merge(schema.requireObjectField("Item", "backing").containingDef).keys().any { it.field.name == "backing" }) {
                                    "backing" setTo 3
                                }
                            }
                        },
                        schema.requireObjectField("Item", "result") to fieldResolverOf(
                            schemas.fragmentFrom("fragment Input on Item { middle { value } }"),
                        ) { input, _ ->
                            val middle = input.selectionValues().getValue("middle")
                            if (middle is EngineErrorData) middle else (middle as EngineObjectData.Sync).selectionValues().getValue("value")
                        },
                        schema.requireObjectField("Item", "middle") to fieldResolverOf(
                            schemas.fragmentFrom("fragment Input on Item { seed(value: ${'$'}provided) }"),
                        ) { input, _ -> schema.objectOf("Leaf") { "value" setTo input.selectionValues().getValue("seed") } }
                            .withVariablesProvider(setOf("provided")) {
                                providerCalls.incrementAndGet()
                                if (providerFails) error("provider failed")
                                mapOf("provided" to 4)
                            },
                        schema.requireObjectField("Item", "seed") to fieldResolverOf(
                            schemas.fragmentFrom("fragment Input on Item { backing }"),
                        ) { input, arguments -> (arguments.fieldValues.getValue("value") as Int) + (input.selectionValues().getValue("backing") as Int) },
                    )
                },
            )
            val result = resolveAndValidate(testWorld, "query { item { result } }", resolverObserver = observer)
            val item = assertIs<ObjectEngineResult>(result.getCell(testWorld.schema.contractKey("Query", "item")).get())

            val value = item.getCell(testWorld.schema.contractKey("Item", "result")).get()
            if (providerFails && !supplied) {
                assertIs<ErrorEngineResult>(value)
            } else {
                assertEquals(if (supplied) 9 else 7, value)
            }
            assertEquals(if (supplied) 0 else 1, providerCalls.get())
            val expected = mutableListOf("Query/item", "Item/result")
            if (!supplied && !providerFails) expected += listOf("Item/middle", "Item/seed")
            assertEquals(expected.sorted(), applications.sorted())
        }
    }

    @Test
    fun `binds provider variables for each grounded resolver occurrence`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!): Int!
                        @resolver(
                          of: "left(value: ${'$'}double) right(value: ${'$'}constant)"
                          providerVars: {
                            double: "sum(${'$'}seed, ${'$'}seed)"
                            constant: 3
                          }
                          result: "sum(left, right)"
                        )
                      left(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      right(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.requireObjectField("Query", "result")
        val firstKey = ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 4))
        val secondKey = ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 5))
        val resolution =
            resolveAndValidateObserved(
                testWorld,
                "query { first: result(seed: 4) second: result(seed: 5) }",
            )

        assertEquals(11, resolution.result.getCell(firstKey).get())
        assertEquals(13, resolution.result.getCell(secondKey).get())
        listOf(
            firstKey to mapOf("double" to 8, "constant" to 3),
            secondKey to mapOf("double" to 10, "constant" to 3),
        ).forEach { (key, expected) ->
            val definitions =
                world.resolverRegistry
                    .resolver(resultField)
                    .instantiatedVariableDefinitions(
                        ResolverOccurrenceId.at(resolution.result, listOf(key)),
                    ).filter { definition ->
                        definition.definition == VariableDefinition.FromProvider
                    }
            assertEquals(
                expected.keys,
                definitions.mapTo(linkedSetOf()) { it.variable.variableName },
            )
            definitions.forEach { definition ->
                assertEquals(
                    VariableBinding.of(expected.getValue(definition.variable.variableName)),
                    resolution.operation.variableBindings.getBinding(
                        requireNotNull(definition.variable.instanceId),
                    ),
                )
            }
        }
    }

    @Test
    fun `provider variables may be null`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int
                        @resolver(
                          of: "consume(value: ${'$'}provided)"
                          providerVars: {provided: null}
                          result: "value(consume)"
                        )
                      consume(value: Int): Int @resolver(result: "value(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.requireObjectField("Query", "result")
        val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())

        val resolved = resolveAndValidate(testWorld, "query { result }")

        assertNull(resolved.getCell(resultKey).get())
    }

    @Test
    fun `provider failure completes its resolver field as an error`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int
                        @resolver(
                          of: "consume(value: ${'$'}provided)"
                          providerVars: {provided: "ERROR"}
                          result: "value(consume)"
                        )
                      consume(value: Int): Int @resolver(result: "value(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.requireObjectField("Query", "result")
        val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())

        val resolved = resolveAndValidate(testWorld, "query { result }")

        assertIs<ErrorEngineResult>(resolved.getCell(resultKey).get())
    }
}
