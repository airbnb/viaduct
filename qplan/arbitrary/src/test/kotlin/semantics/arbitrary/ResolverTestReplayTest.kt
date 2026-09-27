package semantics.arbitrary

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ResolverTestReplayTest {
    @Test
    fun `same seed reproduces generated cases and coordinates`(): Unit =
        runBlocking {
            val first = generatedCases(seed = 8675309L)
            val replay = generatedCases(seed = 8675309L)

            assertEquals(first, replay)
            assertEquals(8, first.size)
            assertEquals(
                "profile=replay-determinism seed=8675309 S=1 R=1 Q=1",
                first.first().coordinates,
            )
            assertEquals(
                "profile=replay-determinism seed=8675309 S=2 R=2 Q=2",
                first.last().coordinates,
            )
        }

    @Test
    fun `coordinate replay reproduces a case selected from the full product`(): Unit =
        runBlocking {
            val random = Random(20260808)

            repeat(32) { trial ->
                val counts =
                    TestCaseCount(
                        schemas = random.nextInt(1, 5),
                        registriesPerSchema = random.nextInt(1, 4),
                        queriesPerSchema = random.nextInt(1, 4),
                    )
                val seed = random.nextLong()
                val selected =
                    ResolverTestCaseCoordinate(
                        schemaIndex = random.nextInt(1, counts.schemas + 1),
                        registryIndex = random.nextInt(1, counts.registriesPerSchema + 1),
                        queryIndex = random.nextInt(1, counts.queriesPerSchema + 1),
                    )
                var expected: GeneratedCaseIdentity? = null

                checkResolverTestCases(
                    counts = counts,
                    config = REPLAY_CONFIG,
                    profile = "replay-coordinate-round-trip",
                    seed = seed,
                ) { _, testCase ->
                    if (testCase.coordinates?.caseCoordinate() == selected) {
                        expected = testCase.identity()
                    }
                }

                val actual =
                    withResolverProperties(
                        RESOLVER_TEST_PROFILE_PROPERTY to "replay-coordinate-round-trip",
                        RESOLVER_TEST_CASE_PROPERTY to selected.summary(),
                    ) {
                        var replayed: GeneratedCaseIdentity? = null
                        val run =
                            checkResolverTestCases(
                                counts = counts,
                                config = REPLAY_CONFIG,
                                profile = "replay-coordinate-round-trip",
                                seed = seed,
                            ) { _, testCase ->
                                replayed = testCase.identity()
                            }

                        assertEquals(1, run.attemptedCases)
                        assertEquals(selected, run.selectedCase)
                        assertNotNull(replayed)
                    }

                assertEquals(
                    assertNotNull(expected),
                    actual,
                    "trial=$trial seed=$seed size=${counts.summary()} case=${selected.summary()}",
                )
            }
        }

    @Test
    fun `case failures report profile seed and product coordinates`(): Unit =
        runBlocking {
            val failure =
                assertFailsWith<AssertionError> {
                    checkResolverTestCases(
                        counts =
                            TestCaseCount(
                                schemas = 2,
                                registriesPerSchema = 2,
                                queriesPerSchema = 2,
                            ),
                        config = REPLAY_CONFIG,
                        profile = "replay-case",
                        seed = 11235813L,
                    ) { _, testCase ->
                        val coordinates = requireNotNull(testCase.coordinates)
                        if (
                            coordinates.schemaIndex == 2 &&
                            coordinates.registryIndex == 2 &&
                            coordinates.queryIndex == 1
                        ) {
                            error("deliberate replay failure")
                        }
                    }
                }
            val failureText = failure.stackTraceToString()

            assertContains(
                failureText,
                "profile=replay-case seed=11235813 S=2 R=2 Q=1",
            )
            assertContains(failureText, "-PresolverPropertySeed=11235813")
            assertContains(failureText, "deliberate replay failure")
        }

    @Test
    fun `aggregate failures report replayable run coordinates`(): Unit =
        runBlocking {
            val run =
                checkResolverTestCases(
                    counts =
                        TestCaseCount(
                            schemas = 1,
                            registriesPerSchema = 1,
                            queriesPerSchema = 1,
                        ),
                    config = REPLAY_CONFIG,
                    profile = "replay-aggregate",
                    seed = 31415926L,
                ) { _, _ -> }

            val failure =
                assertFailsWith<AssertionError> {
                    run.assertAggregate(false, "deliberate aggregate failure")
                }

            assertContains(
                failure.message.orEmpty(),
                "profile=replay-aggregate seed=31415926 S=all R=all Q=all",
            )
            assertContains(
                failure.message.orEmpty(),
                "-PresolverPropertySeed=31415926",
            )
        }

    @Test
    fun `single case replay executes only its product coordinate`(): Unit =
        runBlocking {
            withResolverProperties(
                RESOLVER_TEST_PROFILE_PROPERTY to "replay-single",
                RESOLVER_TEST_CASE_PROPERTY to "2:2:1",
            ) {
                val coordinates = mutableListOf<String>()
                val run =
                    checkResolverTestCases(
                        counts =
                            TestCaseCount(
                                schemas = 3,
                                registriesPerSchema = 2,
                                queriesPerSchema = 2,
                            ),
                        config = REPLAY_CONFIG,
                        profile = "replay-single",
                        seed = 27182818L,
                    ) { _, testCase ->
                        coordinates += requireNotNull(testCase.coordinates).summary()
                    }

                assertEquals(
                    listOf("profile=replay-single seed=27182818 S=2 R=2 Q=1"),
                    coordinates,
                )
                assertEquals(1, run.attemptedCases)
                assertEquals(ResolverTestCaseCoordinate(2, 2, 1), run.selectedCase)
                run.assertAggregate(false, "single-case replay suppresses aggregate guards")
            }
        }

    @Test
    fun `all case replay accepts a custom product size`(): Unit =
        runBlocking {
            withResolverProperties(
                RESOLVER_TEST_PROFILE_PROPERTY to "replay-sized",
                RESOLVER_TEST_CASE_PROPERTY to "all",
                RESOLVER_TEST_SIZE_PROPERTY to "2:1:2",
            ) {
                val coordinates = mutableListOf<String>()
                val run =
                    checkResolverTestCases(
                        counts =
                            TestCaseCount(
                                schemas = 10,
                                registriesPerSchema = 3,
                                queriesPerSchema = 5,
                            ),
                        config = REPLAY_CONFIG,
                        profile = "replay-sized",
                        seed = 16180339L,
                    ) { _, testCase ->
                        coordinates += requireNotNull(testCase.coordinates).summary()
                    }

                assertEquals(4, run.attemptedCases)
                assertEquals(TestCaseCount(2, 1, 2), run.counts)
                assertEquals(4, run.expectedCases)
                assertEquals(null, run.selectedCase)
                assertEquals(
                    "profile=replay-sized seed=16180339 S=2 R=1 Q=2",
                    coordinates.last(),
                )
                assertFailsWith<AssertionError> {
                    run.assertAggregate(false, "all-case replay keeps aggregate guards")
                }
            }
        }

    @Test
    fun `single case replay retains the original custom product`(): Unit =
        runBlocking {
            withResolverProperties(
                RESOLVER_TEST_CASE_PROPERTY to "all",
                RESOLVER_TEST_SIZE_PROPERTY to "2:2:2",
            ) {
                val full = mutableListOf<GeneratedCaseIdentity>()
                checkResolverTestCases(
                    counts = TestCaseCount(1, 1, 1),
                    config = REPLAY_CONFIG,
                    profile = "replay-sized-single",
                    seed = 42L,
                ) { _, testCase -> full += testCase.identity() }
                withResolverProperties(RESOLVER_TEST_CASE_PROPERTY to "2:2:2") {
                    val replayed = mutableListOf<GeneratedCaseIdentity>()
                    val run = checkResolverTestCases(
                        counts = TestCaseCount(1, 1, 1),
                        config = REPLAY_CONFIG,
                        profile = "replay-sized-single",
                        seed = 42L,
                    ) { _, testCase -> replayed += testCase.identity() }
                    assertEquals(listOf(full.last()), replayed)
                    assertEquals(TestCaseCount(2, 2, 2), run.counts)
                    assertEquals(1, run.attemptedCases)
                    run.assertAggregate(false, "single-case replay still suppresses aggregate guards")
                }
            }
        }

    @Test
    fun `coordinate parsers require positive S R Q dimensions`() {
        assertEquals(
            ResolverTestCaseCoordinate(3, 2, 1),
            parseResolverTestCase("3:2:1"),
        )
        assertEquals(TestCaseCount(4, 3, 2), parseResolverTestSize("4:3:2"))
        assertFailsWith<IllegalArgumentException> {
            parseResolverTestCase("0:2:1")
        }
        assertFailsWith<IllegalArgumentException> {
            parseResolverTestSize("2:1")
        }
    }

    private suspend fun generatedCases(seed: Long): List<GeneratedCaseIdentity> {
        val identities = mutableListOf<GeneratedCaseIdentity>()
        val run =
            checkResolverTestCases(
                counts =
                    TestCaseCount(
                        schemas = 2,
                        registriesPerSchema = 2,
                        queriesPerSchema = 2,
                    ),
                config = REPLAY_CONFIG,
                profile = "replay-determinism",
                seed = seed,
            ) { _, testCase ->
                identities += testCase.identity()
            }

        assertEquals(seed, run.seed)
        assertEquals(8, run.attemptedCases)
        return identities
    }

    private data class GeneratedCaseIdentity(
        val coordinates: String?,
        val schema: String,
        val schemaFeatures: SchemaFeatures,
        val registry: String,
        val registryFeatures: RegistryFeatures,
        val fieldValues: Map<FieldCoordinate, ValuePlan>,
        val nodeValues: Map<String, ValuePlan>,
        val objectFragments: Map<FieldCoordinate, FragmentPlan>,
        val variableProviders: List<VariableProviderPlan>,
        val resolverPrograms: Map<FieldCoordinate, ResolverProgramKind>,
        val query: String,
        val permutationEquivalentQuery: String,
        val queryFeatures: QueryFeatures,
    )

    private fun ResolverTestCase.identity(): GeneratedCaseIdentity =
        GeneratedCaseIdentity(
            coordinates = coordinates?.summary(),
            schema = schema.sdl,
            schemaFeatures = schema.features,
            registry = registry.toString(),
            registryFeatures = registry.features,
            fieldValues = registry.fieldValues,
            nodeValues = registry.nodeValues,
            objectFragments = registry.objectFragments,
            variableProviders = registry.variableProviders,
            resolverPrograms = registry.resolverPrograms,
            query = query.source,
            permutationEquivalentQuery = query.permutationEquivalentSource,
            queryFeatures = query.features,
        )

    private fun ResolverTestCoordinates.caseCoordinate(): ResolverTestCaseCoordinate =
        ResolverTestCaseCoordinate(
            schemaIndex = schemaIndex,
            registryIndex = registryIndex,
            queryIndex = queryIndex,
        )

    private fun TestCaseCount.summary(): String =
        "$schemas:$registriesPerSchema:$queriesPerSchema"

    private companion object {
        val REPLAY_CONFIG =
            Config.default +
                (NodeResolversEnabled to false) +
                (ResolverFragmentsEnabled to false) +
                (ResolverFromArgumentVariablesEnabled to false) +
                (ResolverVariablesEnabled to false)
    }
}

private suspend fun <T> withResolverProperties(
    vararg properties: Pair<String, String>,
    block: suspend () -> T,
): T {
    val previous =
        properties.associate { (property, _) ->
            property to System.getProperty(property)
        }
    properties.forEach { (property, value) ->
        System.setProperty(property, value)
    }
    return try {
        block()
    } finally {
        previous.forEach { (property, value) ->
            if (value == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, value)
            }
        }
    }
}
