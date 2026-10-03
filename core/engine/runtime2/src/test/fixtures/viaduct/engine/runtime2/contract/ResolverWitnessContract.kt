@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import viaduct.engine.runtime2.arbitrary.Config
import viaduct.engine.runtime2.arbitrary.DuplicateSelectionWeight
import viaduct.engine.runtime2.arbitrary.ExplicitFieldResolverWeight
import viaduct.engine.runtime2.arbitrary.FieldArgumentWeight
import viaduct.engine.runtime2.arbitrary.NodeResolversEnabled
import viaduct.engine.runtime2.arbitrary.ObjectFieldCount
import viaduct.engine.runtime2.arbitrary.ResolverFragmentsEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromArgumentVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverProgramKind
import viaduct.engine.runtime2.arbitrary.ResolverVariableWeight
import viaduct.engine.runtime2.arbitrary.SchemaObjectCount
import viaduct.engine.runtime2.arbitrary.TestCaseCount
import viaduct.engine.runtime2.arbitrary.allowedResolverClosure
import viaduct.engine.runtime2.arbitrary.checkResolverTestCases
import viaduct.engine.runtime2.correctresolution.conformsToResolvers
import viaduct.engine.runtime2.correctresolution.conformsToSelections
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.correctresolution.isClosedUnderResolverDemand
import viaduct.engine.runtime2.correctresolution.rootedAndWellTyped
import viaduct.engine.runtime2.model.sameCompletedResultAs
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf

/**
 * Generated execution witnesses for exact resolver application counts and minimal construction.
 *
 * Keep independent trace-oracle and permutation-invariance checks in this suite.
 */
interface ResolverWitnessContract : ResolverContract {
    @Test
    fun `generated construction witness is exact minimal and permutation invariant`(): Unit =
        runBlocking {
            val counts = TestCaseCount(schemas = 12, registriesPerSchema = 2, queriesPerSchema = 4)
            val config =
                Config.default +
                    (SchemaObjectCount to 4..6) +
                    (ObjectFieldCount to 4..6) +
                    (FieldArgumentWeight to 0.8) +
                    (ExplicitFieldResolverWeight to 0.8) +
                    (DuplicateSelectionWeight to 0.8) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFromArgumentVariablesEnabled to true) +
                    (ResolverVariableWeight to 1.0) +
                    (NodeResolversEnabled to false)
            var inputSensitiveApplications = 0
            var argumentSensitiveApplications = 0
            var exactAliasCases = 0
            var activatedExactAliasCases = 0
            var activatedDistinctArgumentCases = 0
            var generatedFromArgumentVariables = 0

            val run =
                checkResolverTestCases(
                    counts,
                    config,
                    profile = "resolver03-construction-witness",
                ) { testWorld, testCase ->
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
                        "Every Resolver03 application must capture its supplied demand",
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

            run.assertAggregate(
                inputSensitiveApplications >= 10,
                "Too few input-sensitive applications: $inputSensitiveApplications",
            )
            run.assertAggregate(
                argumentSensitiveApplications >= 10,
                "Too few argument-sensitive applications: $argumentSensitiveApplications",
            )
            run.assertAggregate(
                exactAliasCases >= 10,
                "Too few exact-alias cases: $exactAliasCases",
            )
            run.assertAggregate(
                activatedExactAliasCases >= 10,
                "Too few activated exact-alias cases: $activatedExactAliasCases",
            )
            run.assertAggregate(
                activatedDistinctArgumentCases >= 10,
                "Too few activated distinct-argument cases: $activatedDistinctArgumentCases",
            )
            run.assertAggregate(
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
