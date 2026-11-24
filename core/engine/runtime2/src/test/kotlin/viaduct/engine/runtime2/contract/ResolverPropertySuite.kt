@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.ResolverTestCase
import viaduct.engine.runtime2.arbitrary.ResolverTestRun
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.propertytest.checkResolverTestSuite

fun ResolverContract.checkResolverProperties(
    definition: ResolverTestSuiteDefinition,
    exactApplications: Boolean = false,
    fromFieldBindings: Boolean = false,
): Unit =
    runBlocking {
        val selected = ResolverTestSuiteDefinition.forProfile(
            definition.profile,
            definition.config,
            definition.fieldCheckerMode,
            definition.typeCheckerMode,
        )
        val coverage = resolverPropertyCoverage(selected)
        val assertions = resolverPropertyAssertions(selected, exactApplications, fromFieldBindings)
        val constructionWitness = selected.profile == "construction-witness"
        val run = checkResolverPropertyCases(
            replay = { property -> checkResolverTestSuite(selected, selectiveResolvers = selectiveResolvers, property = property) },
            assertions = assertions,
            captureSuppliedDemand = constructionWitness || selected.profile == "selective-node",
            capturePermutationWitness = constructionWitness,
            record = coverage.record,
        )
        coverage.requireCovered(run)
    }

internal suspend fun ResolverContract.checkResolverPropertyCases(
    replay: suspend (suspend (TestWorld, ResolverTestCase) -> Unit) -> ResolverTestRun,
    assertions: List<GeneratedCaseAssertion>,
    captureSuppliedDemand: Boolean = false,
    capturePermutationWitness: Boolean = false,
    comparePermutation: Boolean = true,
    record: suspend (GeneratedCaseObservation) -> Unit,
): ResolverTestRun =
    replay { world, testCase ->
        val observation = observeGeneratedCase(
            world,
            testCase,
            captureSuppliedDemand = captureSuppliedDemand,
            capturePermutationWitness = capturePermutationWitness,
            comparePermutation = comparePermutation,
        ).assertAll(assertions)
        record(observation)
    }

internal fun resolverPropertyAssertions(
    definition: ResolverTestSuiteDefinition,
    exactApplications: Boolean,
    fromFieldBindings: Boolean,
): List<GeneratedCaseAssertion> =
    buildList {
        addAll(GeneratedCaseAssertions.defaultGeneratedContract)
        if (definition.fieldCheckerMode != GeneratedFieldCheckerMode.NONE || definition.typeCheckerMode != GeneratedTypeCheckerMode.NONE) {
            add(GeneratedCaseAssertions.exactCheckerApplications)
        }
        when (definition.profile) {
            "sometimes-passive" -> add(GeneratedCaseAssertions.boundedOrdinaryApplicationCounts)
            // Retain the root-reference suite's existing exact-accounting exclusion.
            "root-field-reference" -> Unit
            "construction-witness", "list-passive-deepening" -> add(GeneratedCaseAssertions.exactOrdinaryApplicationCounts)
            else -> if (exactApplications) add(GeneratedCaseAssertions.exactOrdinaryApplicationCounts)
        }
        if (fromFieldBindings) add(GeneratedCaseAssertions.fromFieldBindings)
        if (definition.profile == "selective-node") add(GeneratedCaseAssertions.selectiveNodeCallbacks)
        if (definition.profile == "construction-witness") add(GeneratedCaseAssertions.constructionWitness)
    }
