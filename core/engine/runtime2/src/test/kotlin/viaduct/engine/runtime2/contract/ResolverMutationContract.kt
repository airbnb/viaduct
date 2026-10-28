@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.ResolverProgramMutation
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.sameCompletedResultAs
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.checkResolverTestSuite

/** Mutation sensitivity of generated resolver properties. */
interface ResolverMutationContract : ResolverContract {
    @Test
    fun `generated properties reject independent resolver program mutations`(): Unit =
        runBlocking {
            val killed = ResolverProgramMutation.entries.associateWith { 0 }.toMutableMap()
            val exercised = ResolverProgramMutation.entries.associateWith { 0 }.toMutableMap()
            var generatedFromArgumentVariables = 0

            val run =
                checkResolverTestSuite(ResolverTestSuiteDefinition.RESOLVER_MUTATION) { ordinaryWorld, testCase ->
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
                            resolverObserver = registry.resolverObserver(),
                        )
                    val ordinary = ordinaryResolution.result
                    assertTrue(
                        ordinary.correctResolution(
                            ordinaryResolution.operation,
                            ordinaryFragment,
                        ),
                    )

                    ResolverProgramMutation.entries
                        .filterNot { it == ResolverProgramMutation.NONE }
                        .forEach { mutation ->
                            val mutantWorld =
                                registry.world(
                                    schema = testCase.schema,
                                    resolverProgramMutation = mutation,
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
                                        resolverObserver = registry.resolverObserver(resolverProgramMutation = mutation),
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

            ResolverProgramMutation.entries
                .filterNot { it == ResolverProgramMutation.NONE }
                .forEach { mutation ->
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
}
