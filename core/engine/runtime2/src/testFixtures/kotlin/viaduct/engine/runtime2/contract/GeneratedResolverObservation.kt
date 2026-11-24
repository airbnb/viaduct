package viaduct.engine.runtime2.contract

import viaduct.engine.runtime2.arbitrary.ResolverApplicationRecord
import viaduct.engine.runtime2.arbitrary.ResolverTestCase
import viaduct.engine.runtime2.arbitrary.SelectiveNodeResolverApplicationRecord
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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

/** A generated case's executions and the application witnesses requested by its oracle. */
data class GeneratedCaseObservation(
    val testCase: ResolverTestCase,
    val ordinary: GeneratedResolutionObservation,
    val permutationEquivalent: GeneratedResolutionObservation?,
    val ordinaryApplications: List<ResolverApplicationRecord>,
    val selectiveNodeResolverApplications: List<SelectiveNodeResolverApplicationRecord>,
    val permutationApplications: List<ResolverApplicationRecord>? = null,
) {
    val executions: List<GeneratedResolutionObservation>
        get() = listOfNotNull(ordinary, permutationEquivalent)
}

/** Executes an ordinary and permutation-equivalent generated query with fresh assumptions. */
fun ResolverContract.observeGeneratedCase(
    testWorld: TestWorld,
    testCase: ResolverTestCase,
    captureSuppliedDemand: Boolean = false,
    capturePermutationWitness: Boolean = false,
    comparePermutation: Boolean = true,
): GeneratedCaseObservation {
    require(comparePermutation || !capturePermutationWitness) { "Permutation witnesses require executing both queries" }
    testCase.registry.clearResolutionWitness()
    val ordinaryCheckerRecorder = CheckerApplicationRecorder(testCase.registry.resolutionObserver(captureSuppliedDemand = captureSuppliedDemand))
    val ordinary =
        observeGeneratedResolution(
            testWorld = testWorld,
            checkerRecorder = ordinaryCheckerRecorder,
            querySource = testCase.query.source,
        )
    val ordinaryApplications = testCase.registry.resolutionWitness().applications
    val selectiveNodeResolverApplications = testCase.registry.selectiveNodeResolverApplications()
    var permutationApplications: List<ResolverApplicationRecord>? = null
    val permutationEquivalent =
        if (!comparePermutation) {
            null
        } else if (capturePermutationWitness) {
            testCase.registry.clearResolutionWitness()
            observeGeneratedResolution(
                testWorld,
                CheckerApplicationRecorder(testCase.registry.resolutionObserver(captureSuppliedDemand = captureSuppliedDemand)),
                testCase.query.permutationEquivalentSource,
            ).also { permutationApplications = testCase.registry.resolutionWitness().applications }
        } else {
            testCase.registry.withoutResolutionWitnessCapture {
                val checkerRecorder =
                    CheckerApplicationRecorder(
                        testCase.registry.resolutionObserver(captureResolutionWitness = false, captureResolutionApplicationCounts = false)
                    )
                observeGeneratedResolution(
                    testWorld = testWorld,
                    checkerRecorder = checkerRecorder,
                    querySource = testCase.query.permutationEquivalentSource,
                )
            }
        }
    return GeneratedCaseObservation(
        testCase = testCase,
        ordinary = ordinary,
        permutationEquivalent = permutationEquivalent,
        ordinaryApplications = ordinaryApplications,
        selectiveNodeResolverApplications = selectiveNodeResolverApplications,
        permutationApplications = permutationApplications,
    )
}

private fun ResolverContract.observeGeneratedResolution(
    testWorld: TestWorld,
    checkerRecorder: CheckerApplicationRecorder,
    querySource: String,
): GeneratedResolutionObservation {
    val world = testWorld.newAssumptions()
    val fragment = testWorld.schemas.fragmentFrom(querySource)
    val subject =
        observeResolution(
            world,
            world.objectOf("Query"),
            fragment.subselections,
            resolutionObserver = checkerRecorder,
            record = false,
        )
    return GeneratedResolutionObservation(
        operation = subject.operation,
        fragment = fragment,
        subject = subject,
        checkerApplications = checkerRecorder.checkerApplications(),
    )
}
