package viaduct.engine.runtime2.contract

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.groundedArguments
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolution.framework.isContextuallyGrounded
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

/** Contract for field-resolver Query fragments under either OER-ownership policy. */
interface QueryFragmentResolverContract : ResolverContract {
    /** Whether one orchestration shares a singular Query OER among all fragment owners. */
    val usesSingularQueryOER: Boolean
        get() = false
    val singularQueryExpansionDepth: Int
        get() = 8

    @Test
    fun `empty Query fragments follow Query OER ownership policy`() {
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                assertTrue(observation.queryValue.selectionValues().isEmpty())
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query { value: Int! @resolver(qf: "", result: 1) }
                    """.trimIndent(),
            )
        val resolution = resolveAndValidateObserved(testWorld, "query { value }", resolutionObserver = invocationObserver)
        val observer = resolution.operation.resolutionObserver as CorrectnessResolverObserver

        assertEquals(
            1,
            resolution.result
                .getCell(testWorld.assumptions.schema.contractKey("Query", "value"))
                .get(),
        )
        assertTrue(observer.allQueryFragmentResults().isEmpty())
        if (usesSingularQueryOER) {
            assertFalse(observer.allQueryOERs().values.single().isDemanded())
        } else {
            assertTrue(observer.allQueryOERs().isEmpty())
        }
    }

    @Test
    fun `one fromArgument binding is shared by object and query fragments`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      source(value: Int!): Int!
                      consumer(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.loweredSchema.requireObjectField("Query", "source")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        source to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        consumer to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        "fragment ConsumerObject on Query { objectSide: source(value: ${'$'}shared) }",
                                    ),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ConsumerQuery on Query { querySide: source(value: ${'$'}shared) }",
                                    ),
                            ) { input, queryValue, _ ->
                                (input.selectionValues().getValue("objectSide") as Int) +
                                    (queryValue.selectionValues().getValue("querySide") as Int)
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "shared") to
                            schema.loweredSchema.fromArgument(consumer, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val consumerKey =
            world.schema.contractKey("Query", "consumer", mapOf("value" to 7))

        val resolved = resolveAndValidate(testWorld, "query { consumer(value: 7) }")

        assertEquals(14, resolved.getCell(consumerKey).get())
    }

    @Test
    fun `query fragments preserve owner projections and OER ownership policy`() {
        val sourceApplications = AtomicInteger()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                val field = observation.field
                if (field.name == "source") {
                    sourceApplications.incrementAndGet()
                }
            }
        }
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      source(value: Int!): Int!
                      consumer(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.loweredSchema.requireObjectField("Query", "source")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    val queryFragment =
                        schema.fragmentFrom(
                            """
                            fragment ConsumerQuery on Query {
                              aliased: source(value: ${'$'}argumentValue)
                            }
                            """.trimIndent(),
                        )
                    mapOf(
                        source to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment = queryFragment,
                            ) { _, queryValue, _ ->
                                assertEquals(setOf("aliased"), queryValue.selectionValues().keys)
                                queryValue.selectionValues().getValue("aliased")
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "argumentValue") to
                            schema.loweredSchema.fromArgument(consumer, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val firstKey =
            world.schema.contractKey("Query", "consumer", mapOf("value" to 2))
        val secondKey =
            world.schema.contractKey("Query", "consumer", mapOf("value" to 3))

        val resolution =
            resolveAndValidateObserved(
                testWorld,
                """
                query {
                  first: consumer(value: 2)
                  second: consumer(value: 3)
                }
                """.trimIndent(),
                resolutionObserver = invocationObserver,
            )
        val result = resolution.result
        val observations = resolution.operation.resolutionObserver as CorrectnessResolverObserver

        assertEquals(2, result.getCell(firstKey).get())
        assertEquals(3, result.getCell(secondKey).get())
        assertEquals(setOf(firstKey, secondKey), result.keys)
        assertEquals(2, sourceApplications.get())

        val firstQueryResult =
            observations
                .queryFragmentResults(ResolverOccurrenceId.at(result, listOf(firstKey)))
                .single()
        val secondQueryResult =
            observations
                .queryFragmentResults(ResolverOccurrenceId.at(result, listOf(secondKey)))
                .single()
        if (!usesSingularQueryOER) {
            assertNotSame(firstQueryResult, secondQueryResult)
            listOf(firstQueryResult to 2, secondQueryResult to 3).forEach {
                    (queryResult, expectedValue) ->
                val expectedKey =
                    world.schema.contractKey(
                        "Query",
                        "source",
                        mapOf("value" to expectedValue),
                    )
                val actualKey = queryResult.keys.single()
                if (actualKey is ObjectEngineResult.GroundKey) {
                    assertEquals(expectedKey, actualKey)
                } else {
                    assertTrue(actualKey.isContextuallyGrounded(resolution.operation))
                    assertEquals(expectedKey.field, actualKey.field)
                    assertEquals(
                        expectedKey.arguments,
                        actualKey.groundedArguments(resolution.operation),
                    )
                }
            }
            return
        }
        assertSame(firstQueryResult, secondQueryResult)
        assertEquals(setOf(firstQueryResult), observations.allQueryOERs().keys)
        val observedExactKeys =
            firstQueryResult.keys.mapTo(linkedSetOf()) { key ->
                key.field to key.groundedArguments(resolution.operation)
            }
        val queryOER = requireNotNull(observations.queryOER(firstQueryResult))
        assertTrue(queryOER.isDemanded())
        val oerExactKeys =
            queryOER.closedValueSelections
                .instantiateBindings(resolution.operation)
                .byGroundKey()
                .keys
                .mapTo(linkedSetOf()) { key -> key.field to key.arguments }
        assertEquals(
            observedExactKeys,
            oerExactKeys,
        )
        assertEquals(
            setOf(2, 3).mapTo(linkedSetOf()) { expectedValue ->
                world.schema.contractKey(
                    "Query",
                    "source",
                    mapOf("value" to expectedValue),
                )
            },
            firstQueryResult.keys.mapTo(linkedSetOf()) { actualKey ->
                if (actualKey is ObjectEngineResult.GroundKey) {
                    actualKey
                } else {
                    assertFalse(actualKey is ObjectEngineResult.GroundKey)
                    assertTrue(actualKey.isContextuallyGrounded(resolution.operation))
                    ObjectEngineResult.GroundKey.of(
                        actualKey.field,
                        actualKey.groundedArguments(resolution.operation),
                    )
                }
            },
        )
    }

    @Test
    fun `query fragment resolution is transitive`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      base: Int! @resolver(result: 4)
                      middle: Int! @resolver(qf: "value: base", result: "value(query:value)")
                      result: Int! @resolver(qf: "value: middle", result: "value(query:value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")
        val middleKey = world.schema.contractKey("Query", "middle")
        val baseKey = world.schema.contractKey("Query", "base")

        val resolution = resolveAndValidateObserved(testWorld, "query { result }")
        val resolved = resolution.result
        val observations = resolution.operation.resolutionObserver as CorrectnessResolverObserver

        assertEquals(4, resolved.getCell(resultKey).get())
        val middleResult =
            observations
                .queryFragmentResults(ResolverOccurrenceId.at(resolved, listOf(resultKey)))
                .single()
        val baseResult =
            observations
                .queryFragmentResults(ResolverOccurrenceId.at(middleResult, listOf(middleKey)))
                .single()
        if (usesSingularQueryOER) {
            assertSame(middleResult, baseResult)
            assertEquals(setOf(middleKey, baseKey), middleResult.keys)
        } else {
            assertEquals(setOf(middleKey), middleResult.keys)
            assertEquals(setOf(baseKey), baseResult.keys)
        }
    }

    @Test
    fun `object returned inside a Query scope owns a distinct Query OER`() {
        assumeTrue(usesSingularQueryOER)
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      result: Int! @resolver(qf: "container { value }", result: "value(query:container.value)")
                      container: Container! @resolver(result: {})
                      source: Int! @resolver(result: 7)
                    }
                    type Container {
                      value: Int! @resolver(qf: "source", result: "value(query:source)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolution = resolveAndValidateObserved(testWorld, "query { result }")
        val observer = resolution.operation.resolutionObserver as CorrectnessResolverObserver

        assertEquals(
            7,
            resolution.result.getCell(world.schema.contractKey("Query", "result")).get(),
        )
        assertEquals(2, observer.allQueryOERs().values.count { it.isDemanded() })
    }

    @Test
    fun `abstract list elements retain separate containing Query scopes`() {
        assumeTrue(usesSingularQueryOER)
        val sourceAApplications = AtomicInteger()
        val sourceBApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Query {
                      result: [Item!]!
                      items: [Item!]!
                      sourceA: Int!
                      sourceB: Int!
                    }

                    interface Item {
                      value: Int!
                    }

                    type A implements Item {
                      value: Int!
                    }

                    type B implements Item {
                      value: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Query", "result")
                    val items = schema.loweredSchema.requireObjectField("Query", "items")
                    val sourceA = schema.loweredSchema.requireObjectField("Query", "sourceA")
                    val sourceB = schema.loweredSchema.requireObjectField("Query", "sourceB")
                    val valueA = schema.loweredSchema.requireObjectField("A", "value")
                    val valueB = schema.loweredSchema.requireObjectField("B", "value")
                    mapOf(
                        result to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ResultQuery on Query { items { value } }",
                                    ),
                            ) { _, queryValue, _ -> queryValue.outputValue("items") },
                        items to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                listOf(schema.loweredSchema.objectOf("A"), schema.loweredSchema.objectOf("B"))
                            },
                        valueA to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("A"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ValueAQuery on Query { sourceA }",
                                    ),
                            ) { _, queryValue, _ -> queryValue.outputValue("sourceA") },
                        valueB to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("B"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ValueBQuery on Query { sourceB }",
                                    ),
                            ) { _, queryValue, _ -> queryValue.outputValue("sourceB") },
                        sourceA to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                sourceAApplications.incrementAndGet()
                                1
                            },
                        sourceB to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                sourceBApplications.incrementAndGet()
                                2
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val resolution = resolveAndValidateObserved(testWorld, "query { result { value } }")
        val observer = resolution.operation.resolutionObserver as CorrectnessResolverObserver
        val result =
            assertIs<ListEngineResult>(
                resolution.result.getCell(world.schema.contractKey("Query", "result")).get(),
            )
        val first = assertIs<ObjectEngineResult>(result[0].get())
        val second = assertIs<ObjectEngineResult>(result[1].get())

        assertEquals(1, first.getCell(world.schema.contractKey("A", "value")).get())
        assertEquals(2, second.getCell(world.schema.contractKey("B", "value")).get())
        assertEquals(1, sourceAApplications.get())
        assertEquals(1, sourceBApplications.get())
        assertEquals(3, observer.allQueryOERs().values.count { it.isDemanded() })
    }

    @Test
    fun `a failing Query projection does not corrupt an unrelated owner`() {
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                if (observation.field.name == "healthyOwner") {
                    assertEquals(setOf("value"), observation.queryValue.selectionValues().keys)
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      failing: Int! @resolver(result: "ERROR")
                      healthy: Int! @resolver(result: 7)
                      failedOwner: Int! @resolver(qf: "value: failing", result: "value(query:value)")
                      healthyOwner: Int! @resolver(qf: "value: healthy", result: "value(query:value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val failedOwner = world.schema.contractKey("Query", "failedOwner")
        val healthyOwner = world.schema.contractKey("Query", "healthyOwner")

        val resolved = resolveAndValidate(testWorld, "query { failedOwner healthyOwner }", resolutionObserver = invocationObserver)

        assertIs<ErrorEngineResult>(resolved.getCell(failedOwner).get())
        assertEquals(7, resolved.getCell(healthyOwner).get())
    }

    @Test
    fun `finite Query expansion invokes each exact field once`() {
        assumeTrue(usesSingularQueryOER)
        val invocations = linkedMapOf<String, Int>()
        val observer = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                invocations.compute(observation.field.name) { _, count -> (count ?: 0) + 1 }
            }
        }
        val depth = singularQueryExpansionDepth
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    buildString {
                        appendLine("extend type Query {")
                        for (index in 0..depth) {
                            val dependencies =
                                ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { next -> "field$next" }
                            appendLine("  field$index: Int! @resolver(qf: \"$dependencies\", result: $index)")
                        }
                        append("}")
                    },
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                testWorld,
                "query { field0 }",
                resolutionObserver = observer,
            )

        assertEquals(0, resolved.getCell(world.schema.contractKey("Query", "field0")).get())
        assertEquals((0..depth).associate { "field$it" to 1 }, invocations)
    }

    @Test
    fun `query fragment resolver occurrences are distinct from the same request occurrence`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      source(value: Int!): Int! @resolver(result: "value(${'$'}value)")
                      dependency(value: Int!): Int!
                        @resolver(of: "source(value: ${'$'}value)", result: "value(source)")
                      consumer: Int! @resolver(qf: "dependency(value: 7)", result: "value(query:dependency)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val dependencyKey =
            world.schema.contractKey("Query", "dependency", mapOf("value" to 7))
        val consumerKey = world.schema.contractKey("Query", "consumer")

        val resolved =
            resolveAndValidate(
                testWorld,
                "query { dependency(value: 7) consumer }",
            )

        assertEquals(7, resolved.getCell(dependencyKey).get())
        assertEquals(7, resolved.getCell(consumerKey).get())
    }

    @Test
    fun `shared exact Query key retains distinct owner projections`() {
        val sourceApplications = AtomicInteger()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                when (observation.field.name) {
                    "source" -> sourceApplications.incrementAndGet()
                    "first" -> {
                        val queryValue = observation.queryValue
                        assertEquals(setOf("firstSource"), queryValue.selectionValues().keys)
                        val payload = assertIs<EngineObjectData.Sync>(queryValue.selectionValues().getValue("firstSource"))
                        assertEquals(setOf("left"), payload.selectionValues().keys)
                    }
                    "second" -> {
                        val queryValue = observation.queryValue
                        assertEquals(setOf("secondSource"), queryValue.selectionValues().keys)
                        val payload = assertIs<EngineObjectData.Sync>(queryValue.selectionValues().getValue("secondSource"))
                        assertEquals(setOf("right"), payload.selectionValues().keys)
                    }
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      source: Payload! @resolver(result: {left: 1, right: 2})
                      first: Int! @resolver(qf: "firstSource: source { left }", result: "value(query:firstSource.left)")
                      second: Int! @resolver(qf: "secondSource: source { right }", result: "value(query:secondSource.right)")
                    }
                    type Payload { left: Int!, right: Int! }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resolution =
            resolveAndValidateObserved(
                testWorld,
                "query { first second }",
                resolutionObserver = invocationObserver,
            )
        val result = resolution.result
        val observations = resolution.operation.resolutionObserver as CorrectnessResolverObserver
        val firstKey = world.schema.contractKey("Query", "first")
        val secondKey = world.schema.contractKey("Query", "second")

        assertEquals(1, result.getCell(firstKey).get())
        assertEquals(2, result.getCell(secondKey).get())
        val firstQueryResult =
            observations
                .queryFragmentResults(ResolverOccurrenceId.at(result, listOf(firstKey)))
                .single()
        val secondQueryResult =
            observations
                .queryFragmentResults(ResolverOccurrenceId.at(result, listOf(secondKey)))
                .single()
        if (usesSingularQueryOER) {
            assertEquals(1, sourceApplications.get())
            assertSame(firstQueryResult, secondQueryResult)
        } else {
            assertEquals(2, sourceApplications.get())
            assertNotSame(firstQueryResult, secondQueryResult)
        }
    }

    @Test
    fun `selective Query producer receives exact successor demand once`() {
        assumeTrue(usesSingularQueryOER && selectiveResolvers)
        var producerDemand: SelectionForest? = null
        val applications = linkedMapOf<String, Int>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                applications.compute(observation.field.name) { _, count -> (count ?: 0) + 1 }
                when (observation.field.name) {
                    "item" -> {
                        check(producerDemand == null) { "Query-side producer was invoked twice" }
                        producerDemand = observation.suppliedDemand
                    }
                    "owner" -> {
                        val queryItem = assertIs<EngineObjectData.Sync>(observation.queryValue.selectionValues().getValue("item"))
                        assertEquals(setOf("computed"), queryItem.selectionValues().keys)
                    }
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {base: 7})
                      owner: Int! @resolver(qf: "item { computed }", result: "value(query:item.computed)")
                    }
                    type Item {
                      base: Int!
                      computed: Int! @resolver(of: "base", result: "sumplus1(base)")
                      unused: Int! @resolver(result: 99)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(
                testWorld,
                "query { owner }",
                resolutionObserver = invocationObserver,
            )
        val itemType = world.schema.requireType("Item") as ViaductSchema.Object

        assertEquals(
            8,
            result.getCell(world.schema.contractKey("Query", "owner")).get(),
        )
        assertEquals(
            setOf("base", "computed"),
            requireNotNull(producerDemand)
                .merge(itemType)
                .groundKeys()
                .mapTo(linkedSetOf()) { key -> key.field.name },
        )
        assertEquals(1, applications.getValue("item"))
        assertEquals(1, applications.getValue("computed"))
        assertEquals(1, applications.getValue("owner"))
        assertEquals(null, applications["unused"])
    }

    @Test
    fun `diamond owners follow Query OER ownership policy`() {
        val sourceApplications = AtomicInteger()
        val dependencyApplications = AtomicInteger()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                if (observation.field.name == "source") sourceApplications.incrementAndGet()
                if (observation.field.name == "dependency") dependencyApplications.incrementAndGet()
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      source(value: Int!): Int! @resolver(result: "value(${'$'}value)")
                      dependency(value: Int!): Int!
                        @resolver(of: "source(value: ${'$'}value)", result: "value(source)")
                      first: Int! @resolver(qf: "dependency(value: 7)", result: "value(query:dependency)")
                      second: Int! @resolver(qf: "dependency(value: 7)", result: "value(query:dependency)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val firstKey = world.schema.contractKey("Query", "first")
        val secondKey = world.schema.contractKey("Query", "second")

        val resolution =
            resolveAndValidateObserved(
                testWorld,
                "query { first second }",
                resolutionObserver = invocationObserver,
            )
        val resolved = resolution.result
        val observations = resolution.operation.resolutionObserver as CorrectnessResolverObserver

        assertEquals(7, resolved.getCell(firstKey).get())
        assertEquals(7, resolved.getCell(secondKey).get())
        val firstQueryResult =
            observations
                .queryFragmentResults(ResolverOccurrenceId.at(resolved, listOf(firstKey)))
                .single()
        val secondQueryResult =
            observations
                .queryFragmentResults(ResolverOccurrenceId.at(resolved, listOf(secondKey)))
                .single()
        if (usesSingularQueryOER) {
            assertEquals(1, sourceApplications.get())
            assertEquals(1, dependencyApplications.get())
            assertSame(firstQueryResult, secondQueryResult)
        } else {
            assertEquals(2, sourceApplications.get())
            assertEquals(2, dependencyApplications.get())
            assertNotSame(firstQueryResult, secondQueryResult)
        }
    }
}
