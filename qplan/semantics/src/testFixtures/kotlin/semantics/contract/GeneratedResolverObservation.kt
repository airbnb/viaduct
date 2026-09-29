package semantics.contract

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.Assumptions
import model.Fragment
import model.ObjectEngineResult
import model.fragmentFrom
import model.objectOf
import model.sameCompletedResultAs
import model.testing.TestWorld
import semantics.arbitrary.Config
import semantics.arbitrary.ResolverApplicationRecord
import semantics.arbitrary.ResolverTestCase
import semantics.arbitrary.SelectiveNodeResolverApplicationRecord
import semantics.correctresolution.conformsToResolvers
import semantics.correctresolution.conformsToSelections
import semantics.correctresolution.correctResolution
import semantics.correctresolution.isClosedUnderResolverDemand
import semantics.correctresolution.rootedAndWellTyped
import semantics.shared.CheckerInvocationObservation
import semantics.shared.SharedOperationContext

/** One generated resolver execution and the request-local state needed to validate it. */
data class GeneratedResolutionObservation(
    val operation: SharedOperationContext<*>,
    val fragment: Fragment,
    val subject: ResolverResolutionObservation,
    val checkerApplications: List<CheckerInvocationObservation>,
) {
    val world: Assumptions
        get() = operation.world

    val result: ObjectEngineResult
        get() = subject.result
}

/** Both executions of one generated case and the ordinary execution's application witness. */
data class GeneratedCaseObservation(
    val testCase: ResolverTestCase,
    val ordinary: GeneratedResolutionObservation,
    val permutationEquivalent: GeneratedResolutionObservation,
    val ordinaryApplications: List<ResolverApplicationRecord>,
    val selectiveNodeResolverApplications: List<SelectiveNodeResolverApplicationRecord>,
) {
    val executions: List<GeneratedResolutionObservation>
        get() = listOf(ordinary, permutationEquivalent)
}

/** One independently selectable judgment over a completed generated case. */
fun interface GeneratedCaseAssertion {
    fun assertThat(observation: GeneratedCaseObservation)
}

/** Reusable generated-case judgments, composed by each contract according to its claims. */
object GeneratedCaseAssertions {
    val correctResolution =
        GeneratedCaseAssertion { observation ->
            observation.executions.forEach { execution ->
                val correct = execution.result.correctResolution(execution.operation, execution.fragment)
                if (!correct) {
                    fun diagnostic(
                        name: String,
                        value: () -> Any?,
                    ): String =
                        "$name=" +
                            runCatching(value).fold(
                                onSuccess = Any?::toString,
                                onFailure = { failure ->
                                    "${failure::class.simpleName}: ${failure.message}"
                                },
                            )

                    listOf(
                        diagnostic("rootedAndWellTyped") {
                            execution.result.rootedAndWellTyped(execution.world)
                        },
                        diagnostic("conformsToSelections") {
                            execution.result.conformsToSelections(
                                execution.operation,
                                execution.fragment.subselections,
                            )
                        },
                        diagnostic("isClosedUnderResolverDemand") {
                            execution.result.isClosedUnderResolverDemand(execution.operation)
                        },
                        diagnostic("unclosedResolverOccurrences") {
                            execution.result.unclosedRegisteredResolverOccurrences(execution.operation)
                        },
                        diagnostic("conformsToResolvers") {
                            execution.result.conformsToResolvers(execution.operation)
                        },
                    ).joinToString(separator = "\n")
                        .let { diagnostics ->
                            assertTrue(actual = false, message = diagnostics)
                        }
                }
            }
        }

    val permutationEquivalentResult =
        GeneratedCaseAssertion { observation ->
            assertTrue(
                observation.ordinary.result.sameCompletedResultAs(
                    observation.permutationEquivalent.result,
                ),
            )
        }

    val exactOrdinaryApplicationCounts =
        GeneratedCaseAssertion { observation ->
            val expected =
                observation.ordinary.result.registeredResolverApplicationIdentityCounts(observation.ordinary.operation)
            assertEquals(
                expected,
                observation.ordinaryApplications
                    .groupingBy(ResolverApplicationRecord::identity)
                    .eachCount(),
            )
        }

    val fromFieldBindings =
        GeneratedCaseAssertion { observation ->
            observation.executions.forEach { execution ->
                execution.result.validateFromFieldBindings(
                    execution.operation,
                    requireNotNull(execution.subject.appliedResolverOccurrences) {
                        "From-field binding validation requires exact application " +
                            "occurrences"
                    },
                )
            }
        }

    val exactFieldCheckerApplications =
        GeneratedCaseAssertion { observation ->
            observation.executions.forEach { execution ->
                val expected =
                    execution.result.registeredFieldCheckerApplications(execution.operation)
                assertEquals(
                    expected.groupingBy { application -> application }.eachCount(),
                    execution.checkerApplications
                        .groupingBy { application -> application }
                        .eachCount(),
                )
            }
        }

    val defaultGeneratedContract =
        listOf(
            correctResolution,
            permutationEquivalentResult,
        )
}

/** Assertion policy independently extended by each generated resolver test subject. */
interface GeneratedCaseAssertionPolicy : ResolverContract {
    val nodeRootFieldReferencesEnabled: Boolean
        get() = true

    val generatedResolverConfigOverrides: Config
        get() = Config.default

    val generatedCaseAssertions: List<GeneratedCaseAssertion>
        get() = GeneratedCaseAssertions.defaultGeneratedContract
}

fun GeneratedCaseObservation.assertAll(assertions: Iterable<GeneratedCaseAssertion>): GeneratedCaseObservation =
    apply {
        assertions.forEach { assertion -> assertion.assertThat(this) }
    }

/** Executes an ordinary and permutation-equivalent generated query with fresh assumptions. */
fun ResolverContract.observeGeneratedCase(
    testWorld: TestWorld,
    testCase: ResolverTestCase,
    captureSuppliedDemand: Boolean = false,
): GeneratedCaseObservation {
    testCase.registry.clearResolutionWitness()
    val ordinaryCheckerRecorder = CheckerApplicationRecorder()
    val ordinary =
        observeGeneratedResolution(
            testWorld = testWorld,
            resolverObserver = testCase.registry.resolverObserver(captureSuppliedDemand = captureSuppliedDemand),
            checkerRecorder = ordinaryCheckerRecorder,
            querySource = testCase.query.source,
        )
    val ordinaryApplications = testCase.registry.resolutionWitness().applications
    val selectiveNodeResolverApplications = testCase.registry.selectiveNodeResolverApplications()
    val permutationEquivalent =
        testCase.registry.withoutResolutionWitnessCapture {
            val checkerRecorder = CheckerApplicationRecorder()
            observeGeneratedResolution(
                testWorld = testWorld,
                resolverObserver = testCase.registry.resolverObserver(captureResolutionWitness = false, captureResolutionApplicationCounts = false),
                checkerRecorder = checkerRecorder,
                querySource = testCase.query.permutationEquivalentSource,
            )
        }
    return GeneratedCaseObservation(
        testCase = testCase,
        ordinary = ordinary,
        permutationEquivalent = permutationEquivalent,
        ordinaryApplications = ordinaryApplications,
        selectiveNodeResolverApplications = selectiveNodeResolverApplications,
    )
}

private fun ResolverContract.observeGeneratedResolution(
    testWorld: TestWorld,
    resolverObserver: semantics.shared.ResolverObserver,
    checkerRecorder: CheckerApplicationRecorder,
    querySource: String,
): GeneratedResolutionObservation {
    val world = testWorld.newAssumptions(selectiveResolvers)
    val fragment = world.fragmentFrom(querySource)
    val subject =
        observeResolution(
            world,
            world.objectOf("Query"),
            fragment.subselections,
            resolverObserver = resolverObserver,
            checkerObserver = checkerRecorder,
        )
    return GeneratedResolutionObservation(
        operation = subject.operation,
        fragment = fragment,
        subject = subject,
        checkerApplications = checkerRecorder.checkerApplications(),
    )
}
