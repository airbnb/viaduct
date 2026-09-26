package semantics.contract

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import model.Arguments
import model.EngineErrorData
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.ResolverInvocationObservation
import semantics.shared.groundedArguments
import semantics.shared.isContextuallyGrounded

/** Contract for field-resolver Query fragments under either OER-ownership policy. */
interface QueryFragmentResolverContract : ResolverContract {
    /** Whether one orchestration shares a singular Query OER among all fragment owners. */
    val usesSingularQueryOER: Boolean
        get() = false

    @Test
    fun `empty Query fragments follow Query OER ownership policy`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL = "type Query { value: Int! }",
                fieldResolvers = { schema ->
                    val value = schema.requireObjectField("Query", "value")
                    mapOf(
                        value to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = schema.emptyFragmentOf("Query"),
                            ) { _, queryValue, _ ->
                                assertTrue(queryValue.selectionValues().isEmpty())
                                1
                            },
                    )
                },
            )
        val resolution = resolveAndValidateObserved(testWorld.assumptions, "query { value }")
        val observer = resolution.operation.resolverObserver as CorrectnessResolverObserver

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
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      source(value: Int!): Int!
                      consumer(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.requireObjectField("Query", "source")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
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
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "shared") to
                            schema.fromArgument(consumer, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val consumerKey =
            world.schema.contractKey("Query", "consumer", mapOf("value" to 7))

        val resolved = resolveAndValidate(world, "query { consumer(value: 7) }")

        assertEquals(14, resolved.getCell(consumerKey).get())
    }

    @Test
    fun `query fragments preserve owner projections and OER ownership policy`() {
        val sourceApplications = AtomicInteger()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                if (field.name == "source") {
                    sourceApplications.incrementAndGet()
                }
            }
        }
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      source(value: Int!): Int!
                      consumer(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.requireObjectField("Query", "source")
                    val consumer = schema.requireObjectField("Query", "consumer")
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
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = queryFragment,
                            ) { _, queryValue, _ ->
                                assertEquals(setOf("aliased"), queryValue.selectionValues().keys)
                                queryValue.selectionValues().getValue("aliased")
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "argumentValue") to
                            schema.fromArgument(consumer, "value"),
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
                world,
                """
                query {
                  first: consumer(value: 2)
                  second: consumer(value: 3)
                }
                """.trimIndent(),
                resolverObserver = invocationObserver,
            )
        val result = resolution.result
        val observations = resolution.operation.resolverObserver as CorrectnessResolverObserver

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
            queryOER.closedDemand
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
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      base: Int!
                      middle: Int!
                      result: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val base = schema.requireObjectField("Query", "base")
                    val middle = schema.requireObjectField("Query", "middle")
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        base to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 4 },
                        middle to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment MiddleQuery on Query { value: base }",
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("value")
                            },
                        result to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ResultQuery on Query { value: middle }",
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("value")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")
        val middleKey = world.schema.contractKey("Query", "middle")
        val baseKey = world.schema.contractKey("Query", "base")

        val resolution = resolveAndValidateObserved(world, "query { result }")
        val resolved = resolution.result
        val observations = resolution.operation.resolverObserver as CorrectnessResolverObserver

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
    fun `a failing Query projection does not corrupt an unrelated owner`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      failing: Int!
                      healthy: Int!
                      failedOwner: Int!
                      healthyOwner: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val failing = schema.requireObjectField("Query", "failing")
                    val healthy = schema.requireObjectField("Query", "healthy")
                    val failedOwner = schema.requireObjectField("Query", "failedOwner")
                    val healthyOwner = schema.requireObjectField("Query", "healthyOwner")
                    mapOf(
                        failing to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                EngineErrorData.of()
                            },
                        healthy to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        failedOwner to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom("fragment Failed on Query { value: failing }"),
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("value")
                            },
                        healthyOwner to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom("fragment Healthy on Query { value: healthy }"),
                            ) { _, queryValue, _ ->
                                assertEquals(setOf("value"), queryValue.selectionValues().keys)
                                queryValue.selectionValues().getValue("value")
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val failedOwner = world.schema.contractKey("Query", "failedOwner")
        val healthyOwner = world.schema.contractKey("Query", "healthyOwner")

        val resolved = resolveAndValidate(world, "query { failedOwner healthyOwner }")

        assertIs<ErrorEngineResult>(resolved.getCell(failedOwner).get())
        assertEquals(7, resolved.getCell(healthyOwner).get())
    }

    @Test
    fun `depth eight Query expansion invokes each exact field once`() {
        assumeTrue(usesSingularQueryOER)
        val invocations = linkedMapOf<String, Int>()
        val observer = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                invocations.compute(observation.field.name) { _, count -> (count ?: 0) + 1 }
            }
        }
        val depth = 8
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    buildString {
                        appendLine("type Query {")
                        (0..depth).forEach { index -> appendLine("  field$index: Int!") }
                        append("}")
                    },
                fieldResolvers = { schema ->
                    (0..depth).associate { index ->
                        val field = schema.requireObjectField("Query", "field$index")
                        val dependencies =
                            ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { next ->
                                "field$next"
                            }
                        val queryFragment =
                            if (dependencies.isEmpty()) {
                                schema.emptyFragmentOf("Query")
                            } else {
                                schema.fragmentFrom(
                                    "fragment Field${index}Query on Query { $dependencies }",
                                )
                            }
                        field to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = queryFragment,
                            ) { _, _, _ -> index }
                    }
                },
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                "query { field0 }",
                resolverObserver = observer,
            )

        assertEquals(0, resolved.getCell(world.schema.contractKey("Query", "field0")).get())
        assertEquals((0..depth).associate { "field$it" to 1 }, invocations)
    }

    @Test
    fun `query fragment resolver occurrences are distinct from the same request occurrence`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      source(value: Int!): Int!
                      dependency(value: Int!): Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.requireObjectField("Query", "source")
                    val dependency = schema.requireObjectField("Query", "dependency")
                    val consumer = schema.requireObjectField("Query", "consumer")
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        dependency to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        "fragment Dependency on Query { source(value: ${'$'}value) }",
                                    ),
                            ) { input, _ ->
                                input.selectionValues().getValue("source")
                            },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ConsumerQuery on Query { dependency(value: 7) }",
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("dependency")
                            },
                    )
                },
                variableProviders = { schema ->
                    val dependency = schema.requireObjectField("Query", "dependency")
                    mapOf(
                        Arguments.Variable.of(dependency, "value") to
                            schema.fromArgument(dependency, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val dependencyKey =
            world.schema.contractKey("Query", "dependency", mapOf("value" to 7))
        val consumerKey = world.schema.contractKey("Query", "consumer")

        val resolved =
            resolveAndValidate(
                world,
                "query { dependency(value: 7) consumer }",
            )

        assertEquals(7, resolved.getCell(dependencyKey).get())
        assertEquals(7, resolved.getCell(consumerKey).get())
    }

    @Test
    fun `diamond owners follow Query OER ownership policy`() {
        val sourceApplications = AtomicInteger()
        val dependencyApplications = AtomicInteger()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                if (observation.field.name == "source") sourceApplications.incrementAndGet()
                if (observation.field.name == "dependency") dependencyApplications.incrementAndGet()
            }
        }
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      source(value: Int!): Int!
                      dependency(value: Int!): Int!
                      first: Int!
                      second: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val source = schema.requireObjectField("Query", "source")
                    val dependency = schema.requireObjectField("Query", "dependency")
                    val first = schema.requireObjectField("Query", "first")
                    val second = schema.requireObjectField("Query", "second")
                    val dependencyQuery =
                        schema.fragmentFrom(
                            "fragment ConsumerQuery on Query { dependency(value: 7) }",
                        )
                    mapOf(
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        dependency to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        "fragment Dependency on Query { source(value: ${'$'}value) }",
                                    ),
                            ) { input, _ ->
                                input.selectionValues().getValue("source")
                            },
                        first to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = dependencyQuery,
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("dependency")
                            },
                        second to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = dependencyQuery,
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("dependency")
                            },
                    )
                },
                variableProviders = { schema ->
                    val dependency = schema.requireObjectField("Query", "dependency")
                    mapOf(
                        Arguments.Variable.of(dependency, "value") to
                            schema.fromArgument(dependency, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val firstKey = world.schema.contractKey("Query", "first")
        val secondKey = world.schema.contractKey("Query", "second")

        val resolution =
            resolveAndValidateObserved(
                world,
                "query { first second }",
                resolverObserver = invocationObserver,
            )
        val resolved = resolution.result
        val observations = resolution.operation.resolverObserver as CorrectnessResolverObserver

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
