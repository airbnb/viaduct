@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.ResolutionFingerprint
import viaduct.engine.runtime2.arbitrary.ResolverProgramMutation
import viaduct.engine.runtime2.arbitrary.SelectiveNodeResolverApplicationRecord
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.sameCompletedResultAs
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.checkResolverTestSuite
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/** Mutation sensitivity of resolver programs and shared oracle judgments. */
interface ResolverMutationContract : ResolverContract {
    @Test
    fun `generated properties reject independent resolver program mutations`(): Unit =
        runBlocking {
            val mutations =
                ResolverProgramMutation.entries.filterNot { mutation ->
                    mutation == ResolverProgramMutation.NONE ||
                        mutation == ResolverProgramMutation.IGNORE_PARENT_READING_INPUT ||
                        mutation == ResolverProgramMutation.IGNORE_DEEP_PARENT_DEMAND_INPUT ||
                        mutation == ResolverProgramMutation.IGNORE_QUERY_FRAGMENT_INPUT
                }
            val killed = mutations.associateWith { 0 }.toMutableMap()
            val exercised = mutations.associateWith { 0 }.toMutableMap()
            var generatedFromArgumentVariables = 0

            val run =
                checkResolverTestSuite(ResolverTestSuiteDefinition.RESOLVER_MUTATION, selectiveResolvers = selectiveResolvers) { ordinaryWorld, testCase ->
                    val registry = testCase.registry
                    generatedFromArgumentVariables += registry.features.fromArgumentVariableCount
                    val ordinaryAssumptions = ordinaryWorld.newAssumptions()
                    val ordinaryFragment =
                        ordinaryWorld.schemas.fragmentFrom(testCase.query.source)
                    registry.clearResolutionWitness()
                    val ordinaryResolution =
                        observeResolution(
                            ordinaryAssumptions,
                            ordinaryAssumptions.objectOf("Query"),
                            ordinaryFragment.subselections,
                            resolutionObserver = registry.resolutionObserver(),
                            record = false,
                        )
                    val ordinary = ordinaryResolution.result
                    assertTrue(
                        ordinary.correctResolution(
                            ordinaryResolution.operation,
                            ordinaryFragment,
                        ),
                    )

                    mutations.forEach { mutation ->
                        val mutantWorld =
                            registry.world(
                                schema = testCase.schema,
                                resolverProgramMutation = mutation,
                                selectiveResolvers = selectiveResolvers,
                            )
                        val mutantAssumptions = mutantWorld.newAssumptions()
                        registry.clearResolutionWitness()
                        val mutantResult =
                            runCatching {
                                val fragment = mutantWorld.schemas.fragmentFrom(testCase.query.source)
                                observeResolution(
                                    mutantAssumptions,
                                    mutantAssumptions.objectOf("Query"),
                                    fragment.subselections,
                                    resolutionObserver = registry.resolutionObserver(resolverProgramMutation = mutation),
                                    record = false,
                                )
                            }
                        val witness = registry.resolutionWitness()
                        if (witness.applications.isNotEmpty()) {
                            exercised[mutation] = exercised.getValue(mutation) + 1
                        }
                        val rejected =
                            mutantResult.fold(
                                onSuccess = { resolution ->
                                    val result = resolution.result
                                    when (mutation) {
                                        ResolverProgramMutation.DUPLICATE_APPLICATION ->
                                            witness.applicationIdentityCounts() !=
                                                result
                                                    .registeredResolverApplicationIdentityCounts(resolution.operation)
                                        else -> !result.sameCompletedResultAs(ordinary)
                                    }
                                },
                                onFailure = { true },
                            )
                        if (rejected) {
                            killed[mutation] = killed.getValue(mutation) + 1
                        }
                    }
                }

            mutations.forEach { mutation ->
                run.requireCoverage(
                    exercised.getValue(mutation) >= 100,
                    "$mutation was exercised too rarely: ${exercised.getValue(mutation)}",
                )
                run.requireCoverage(
                    killed.getValue(mutation) >= 10,
                    "$mutation survived too often: killed=${killed.getValue(mutation)}",
                )
            }
            run.requireCoverage(generatedFromArgumentVariables > 0, "Generated no FromArgument variables")
        }

    @Test
    fun `generated parent-reading resolver inputs reach input-sensitive resolver outputs`(): Unit =
        runBlocking {
            val coverage = parentReadingResolverInputCoverage()
            val run = checkResolverTestSuite(ResolverTestSuiteDefinition.RESOLUTION_PARENT_FIELDS, selectiveResolvers = selectiveResolvers) { world, testCase ->
                coverage.record(observeGeneratedCase(world, testCase, comparePermutation = false))
            }
            coverage.requireCovered(run)
        }

    @Test
    fun `shared correctness judgment rejects missing result fields`(): Unit =
        withObservation { observation ->
            GeneratedCaseAssertions.correctResolution.assertThat(observation)
            val ordinary = observation.ordinary
            val incomplete = ObjectEngineResult.of(type = ordinary.world.schema.requireQueryTypeDef(), values = emptyMap())
            val subject = object : ResolverResolutionObservation by ordinary.subject {
                override val result = incomplete
            }

            assertFailsWith<IllegalStateException> {
                GeneratedCaseAssertions.correctResolution.assertThat(observation.copy(ordinary = ordinary.copy(subject = subject)))
            }
        }

    @Test
    fun `shared exact resolver accounting rejects missing and duplicate applications`(): Unit =
        withObservation { observation ->
            GeneratedCaseAssertions.exactOrdinaryApplicationCounts.assertThat(observation)
            val applications = observation.ordinaryApplications
            assertTrue(applications.isNotEmpty())

            listOf(applications.drop(1), applications + applications.first()).forEach { mutated ->
                assertFailsWith<IllegalStateException> {
                    GeneratedCaseAssertions.exactOrdinaryApplicationCounts.assertThat(observation.copy(ordinaryApplications = mutated))
                }
            }
        }

    @Test
    fun `shared exact checker accounting rejects missing and duplicate field and type applications`(): Unit =
        withObservation(ResolverTestSuiteDefinition.RESOLUTION_TYPE_CHECKER_SUCCESS) { observation ->
            GeneratedCaseAssertions.exactCheckerApplications.assertThat(observation)
            val applications = observation.ordinary.checkerApplications

            listOf(CheckerKind.FIELD, CheckerKind.TYPE).forEach { kind ->
                val application = applications.first { it.checkerKind == kind }
                listOf(applications.toMutableList().apply { remove(application) }, applications + application).forEach { mutated ->
                    assertFailsWith<IllegalStateException> {
                        GeneratedCaseAssertions.exactCheckerApplications.assertThat(observation.copy(ordinary = observation.ordinary.copy(checkerApplications = mutated)))
                    }
                }
            }
        }

    @Test
    fun `shared binding judgment rejects missing provider bindings`(): Unit =
        withObservation(ResolverTestSuiteDefinition.RESOLUTION_OBJECT_FRAGMENT_FROM_OBJECT_FIELD) { observation ->
            GeneratedCaseAssertions.fromFieldBindings.assertThat(observation)
            val ordinary = observation.ordinary
            val unbound = SharedOperationContext.create(ordinary.world, resolutionObserver = ordinary.operation.resolutionObserver)
            val subject = object : ResolverResolutionObservation by ordinary.subject {
                override val operation = unbound
            }

            assertFailsWith<AssertionError> {
                GeneratedCaseAssertions.fromFieldBindings.assertThat(observation.copy(ordinary = ordinary.copy(operation = unbound, subject = subject)))
            }
        }

    @Test
    fun `permutation comparison rejects changed resolver input witnesses`(): Unit =
        withObservation { observation ->
            val permuted = requireNotNull(observation.permutationApplications).toMutableList()
            permuted[0] = permuted[0].copy(inputFingerprint = ResolutionFingerprint("incorrect input"))

            assertFailsWith<IllegalStateException> {
                GeneratedCaseAssertions.constructionWitness.assertThat(observation.copy(permutationApplications = permuted))
            }
        }

    @Test
    fun `construction witness rejects missing demand recordings and unrelated applications`(): Unit =
        withObservation { observation ->
            val applications = observation.ordinaryApplications.toMutableList()
            applications[0] = applications[0].copy(suppliedDemandFingerprint = null)
            assertFailsWith<IllegalStateException> {
                GeneratedCaseAssertions.constructionWitness.assertThat(observation.copy(ordinaryApplications = applications))
            }

            val unrelated = observation.ordinaryApplications.first().let { it.copy(key = it.key.copy(field = FieldCoordinate("Query", "unrelated"))) }
            assertFailsWith<IllegalStateException> {
                GeneratedCaseAssertions.constructionWitness.assertThat(observation.copy(ordinaryApplications = observation.ordinaryApplications + unrelated))
            }
        }

    @Test
    fun `permutation judgments require both executions and their recordings`(): Unit =
        withObservation { observation ->
            assertFailsWith<IllegalArgumentException> {
                GeneratedCaseAssertions.permutationEquivalentResult.assertThat(observation.copy(permutationEquivalent = null))
            }
            assertFailsWith<IllegalArgumentException> {
                GeneratedCaseAssertions.constructionWitness.assertThat(observation.copy(permutationApplications = null))
            }
        }

    @Test
    fun `bounded application accounting permits skipped resolvers but rejects excess applications`(): Unit =
        withObservation { observation ->
            GeneratedCaseAssertions.boundedOrdinaryApplicationCounts.assertThat(observation.copy(ordinaryApplications = emptyList()))
            assertFailsWith<IllegalStateException> {
                GeneratedCaseAssertions.boundedOrdinaryApplicationCounts.assertThat(
                    observation.copy(ordinaryApplications = observation.ordinaryApplications + observation.ordinaryApplications),
                )
            }
        }

    @Test
    fun `node callback accounting rejects a callback without a loader application`(): Unit =
        withObservation { observation ->
            assertFailsWith<IllegalStateException> {
                GeneratedCaseAssertions.selectiveNodeCallbacks.assertThat(
                    observation.copy(selectiveNodeResolverApplications = listOf(SelectiveNodeResolverApplicationRecord("Node", "1", ResolutionFingerprint("demand")))),
                )
            }
        }

    private fun withObservation(
        definition: ResolverTestSuiteDefinition = ResolverTestSuiteDefinition.CONSTRUCTION_WITNESS,
        assertion: (GeneratedCaseObservation) -> Unit,
    ): Unit =
        runBlocking {
            val suite = ResolverTestSuite.load(definition)
            val witness = definition == ResolverTestSuiteDefinition.CONSTRUCTION_WITNESS
            suite.replay(selected = suite.testCaseIds().first()) { world, testCase ->
                assertion(observeGeneratedCase(world, testCase, captureSuppliedDemand = witness, capturePermutationWitness = witness))
            }
        }
}
