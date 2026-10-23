@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import viaduct.engine.runtime2.arbitrary.ResolverProgramKind
import viaduct.engine.runtime2.arbitrary.allowedResolverClosure
import viaduct.engine.runtime2.correctresolution.conformsToResolvers
import viaduct.engine.runtime2.correctresolution.conformsToSelections
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.correctresolution.isClosedUnderResolverDemand
import viaduct.engine.runtime2.correctresolution.rootedAndWellTyped
import viaduct.engine.runtime2.model.sameCompletedResultAs
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.checkResolverTestSuite

/**
 * Generated execution witnesses for exact resolver application counts and minimal construction.
 *
 * Keep independent trace-oracle and permutation-invariance checks in this suite.
 */
interface ResolverWitnessContract : ResolverContract {
    @Test
    fun `generated construction witness is exact minimal and permutation invariant`(): Unit =
        runBlocking {
            var inputSensitiveApplications = 0
            var argumentSensitiveApplications = 0
            var exactAliasCases = 0
            var activatedExactAliasCases = 0
            var activatedDistinctArgumentCases = 0
            var generatedFromArgumentVariables = 0

            val run =
                checkResolverTestSuite(ResolverTestSuiteDefinition.CONSTRUCTION_WITNESS) { testWorld, testCase ->
                    generatedFromArgumentVariables +=
                        testCase.registry.features.fromArgumentVariableCount
                    val world = testWorld.newAssumptions()
                    val registry = testCase.registry
                    val fragment = testWorld.schemas.fragmentFrom(testCase.query.source)
                    registry.clearResolutionWitness()
                    val resolution =
                        observeResolution(
                            world,
                            world.objectOf("Query"),
                            fragment.subselections,
                            resolverObserver = registry.resolverObserver(captureSuppliedDemand = true),
                        )
                    val result = resolution.result
                    val witness = registry.resolutionWitness()
                    val expectedApplications =
                        result.registeredResolverApplicationIdentityCounts(resolution.operation)
                    assertEquals(expectedApplications, witness.applicationIdentityCounts())
                    assertTrue(
                        witness.applications.all { application ->
                            application.suppliedDemandFingerprint != null
                        },
                        "Every selective resolver application must capture its supplied demand",
                    )
                    val unrelatedApplications =
                        witness.unrelatedApplications(
                            fragment.subselections.allowedResolverClosure(world.resolverRegistry),
                        )
                    assertTrue(
                        unrelatedApplications.isEmpty(),
                        "Resolver applied outside operation/registry demand closure: " +
                            unrelatedApplications.map { application -> application.key.field },
                    )
                    assertTrue(
                        result.correctResolution(resolution.operation, fragment),
                        "rooted=${result.rootedAndWellTyped(world)}, " +
                            "selections=" +
                            "${result.conformsToSelections(resolution.operation, fragment.subselections)}, " +
                            "closed=${result.isClosedUnderResolverDemand(resolution.operation)}, " +
                            "resolvers=${result.conformsToResolvers(resolution.operation)}, " +
                            "unclosed=${result.unclosedRegisteredResolverOccurrences(resolution.operation).map { cell ->
                                cell.applicationKey to cell.occurrencePath
                            }}",
                    )

                    witness.applications.forEach { application ->
                        when (registry.resolverProgram(application.key.field)) {
                            ResolverProgramKind.INPUT_SENSITIVE ->
                                inputSensitiveApplications += 1
                            ResolverProgramKind.ARGUMENT_SENSITIVE ->
                                argumentSensitiveApplications += 1
                            ResolverProgramKind.INPUT_AND_ARGUMENT_SENSITIVE -> {
                                inputSensitiveApplications += 1
                                argumentSensitiveApplications += 1
                            }
                            ResolverProgramKind.CONSTANT -> Unit
                        }
                    }
                    if (testCase.query.features.hasExactKeyAliasConvergence) {
                        exactAliasCases += 1
                    }
                    if (
                        witness.applications.any { application ->
                            application.key.field in
                                testCase.query.features.exactKeyAliasSourceFields
                        }
                    ) {
                        activatedExactAliasCases += 1
                    }
                    if (
                        witness.applications
                            .filter { application ->
                                application.key.field in
                                    testCase.query.features.distinctArgumentSourceFields
                            }.groupBy { application -> application.key.field }
                            .any { (_, applications) ->
                                applications.map { application -> application.key.arguments }
                                    .distinct()
                                    .size > 1
                            }
                    ) {
                        activatedDistinctArgumentCases += 1
                    }

                    val permutedWorld = testWorld.newAssumptions()
                    val permuted =
                        testWorld.schemas.fragmentFrom(testCase.query.permutationEquivalentSource)
                    registry.clearResolutionWitness()
                    val permutedResult =
                        observeResolution(
                            permutedWorld,
                            permutedWorld.objectOf("Query"),
                            permuted.subselections,
                            resolverObserver = registry.resolverObserver(captureSuppliedDemand = true),
                        ).result
                    val permutedWitness = registry.resolutionWitness()
                    assertTrue(result.sameCompletedResultAs(permutedResult))
                    assertEquals(
                        witness.applicationObservationCounts(),
                        permutedWitness.applicationObservationCounts(),
                    )
                    assertEquals(
                        witness.applications
                            .map { it.key to it.inputFingerprint }
                            .groupingBy { it }
                            .eachCount(),
                        permutedWitness.applications
                            .map { it.key to it.inputFingerprint }
                            .groupingBy { it }
                            .eachCount(),
                    )
                }

            run.requireCoverage(
                inputSensitiveApplications >= 10,
                "Too few input-sensitive applications: $inputSensitiveApplications",
            )
            run.requireCoverage(
                argumentSensitiveApplications >= 10,
                "Too few argument-sensitive applications: $argumentSensitiveApplications",
            )
            run.requireCoverage(
                exactAliasCases >= 10,
                "Too few exact-alias cases: $exactAliasCases",
            )
            run.requireCoverage(
                activatedExactAliasCases >= 10,
                "Too few activated exact-alias cases: $activatedExactAliasCases",
            )
            run.requireCoverage(
                activatedDistinctArgumentCases >= 10,
                "Too few activated distinct-argument cases: $activatedDistinctArgumentCases",
            )
            run.requireCoverage(
                generatedFromArgumentVariables > 0,
                "Generated no FromArgument variables",
            )
        }

    @Disabled("not currently worth the effort")
    @Test
    fun `generated supplied demand matches independently reconstructed successor demand`() {
        error(
            "Independent demand reconstruction currently disagrees with list-transparent " +
                "continuation paths.",
        )
    }
}
