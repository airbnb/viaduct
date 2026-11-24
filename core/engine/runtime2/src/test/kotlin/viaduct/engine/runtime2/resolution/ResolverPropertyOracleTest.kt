@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.arbitrary.ResolverFromObjectFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverFromQueryFieldVariablesEnabled
import viaduct.engine.runtime2.arbitrary.ResolverVariablesEnabled
import viaduct.engine.runtime2.contract.GeneratedCaseAssertions
import viaduct.engine.runtime2.contract.GeneratedCaseObservation
import viaduct.engine.runtime2.contract.ResolverContract
import viaduct.engine.runtime2.contract.assertAll
import viaduct.engine.runtime2.contract.observeGeneratedCase
import viaduct.engine.runtime2.contract.queryFragmentCoverage
import viaduct.engine.runtime2.contract.resolverPropertyAssertions
import viaduct.engine.runtime2.contract.resolverPropertyCoverage
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class ResolverPropertyOracleTest : ResolverContract, ResolutionDispatcherResource {
    private var resolutionCalls = 0

    override fun resolve(
        operation: SharedOperationContext<*>,
        root: EngineObjectData.Sync,
        selections: SelectionForest,
    ): ObjectEngineResult {
        resolutionCalls += 1
        return operation.resolveWithTestDispatcher(selections)
    }

    @Test
    fun `both query executions retain independent valid construction witnesses`(): Unit =
        withObservation { observation ->
            assertTrue(observation.ordinaryApplications.isNotEmpty())
            assertTrue(requireNotNull(observation.permutationApplications).isNotEmpty())
            assertEquals(2, resolutionCalls)
            observation.assertAll(
                GeneratedCaseAssertions.defaultGeneratedContract +
                    GeneratedCaseAssertions.exactOrdinaryApplicationCounts +
                    GeneratedCaseAssertions.constructionWitness,
            )
        }

    @Test
    fun `single execution mode checks the result without rerunning the query`(): Unit =
        withObservation(comparePermutation = false) { observation ->
            assertEquals(1, resolutionCalls)
            assertNull(observation.permutationEquivalent)
            observation.assertAll(listOf(GeneratedCaseAssertions.correctResolution, GeneratedCaseAssertions.exactOrdinaryApplicationCounts))
        }

    @Test
    fun `ordinary suites without coverage definitions fail before executing`() {
        assertThrows<IllegalArgumentException> {
            resolverPropertyCoverage(ResolverTestSuiteDefinition.RESOLVER_MUTATION)
        }
    }

    @Test
    fun `suite assertion selection retains exactness and source supplied exceptions`() {
        val ordinary = resolverPropertyAssertions(ResolverTestSuiteDefinition.RESOLUTION_OBJECT_FRAGMENT_FROM_OBJECT_FIELD, exactApplications = true, fromFieldBindings = true)
        assertTrue(ordinary.containsAll(GeneratedCaseAssertions.defaultGeneratedContract + GeneratedCaseAssertions.exactOrdinaryApplicationCounts + GeneratedCaseAssertions.fromFieldBindings))
        val checker = resolverPropertyAssertions(ResolverTestSuiteDefinition.RESOLUTION_TYPE_CHECKER_SUCCESS, exactApplications = false, fromFieldBindings = false)
        assertTrue(GeneratedCaseAssertions.exactCheckerApplications in checker)
        val sometimesPassive = resolverPropertyAssertions(ResolverTestSuiteDefinition.SOMETIMES_PASSIVE, exactApplications = true, fromFieldBindings = false)
        assertTrue(GeneratedCaseAssertions.boundedOrdinaryApplicationCounts in sometimesPassive)
        assertFalse(GeneratedCaseAssertions.exactOrdinaryApplicationCounts in sometimesPassive)
        val references = resolverPropertyAssertions(ResolverTestSuiteDefinition.ROOT_FIELD_REFERENCE, exactApplications = true, fromFieldBindings = true)
        assertFalse(GeneratedCaseAssertions.exactOrdinaryApplicationCounts in references)
        assertTrue(GeneratedCaseAssertions.fromFieldBindings in references)
    }

    @Test
    fun `query-fragment coverage only requires enabled variable sources`(): Unit =
        runBlocking {
            data class Policy(val enabled: Boolean, val objectFields: Boolean, val queryFields: Boolean, val shouldReject: Boolean)
            val definition = ResolverTestSuiteDefinition.QUERY_FRAGMENT
            val policies = listOf(
                Policy(enabled = false, objectFields = true, queryFields = false, shouldReject = false),
                Policy(enabled = true, objectFields = false, queryFields = false, shouldReject = false),
                Policy(enabled = false, objectFields = true, queryFields = true, shouldReject = false),
                Policy(enabled = true, objectFields = true, queryFields = false, shouldReject = true),
                Policy(enabled = true, objectFields = false, queryFields = true, shouldReject = true),
            ).associateWith { policy ->
                queryFragmentCoverage(
                    definition.config +
                        (ResolverVariablesEnabled to policy.enabled) +
                        (ResolverFromObjectFieldVariablesEnabled to policy.objectFields) +
                        (ResolverFromQueryFieldVariablesEnabled to policy.queryFields),
                )
            }
            val suite = ResolverTestSuite.load(definition)
            val run = suite.replay(selected = null) { world, testCase ->
                val observation = observeGeneratedCase(world, testCase, comparePermutation = false)
                policies.values.forEach { coverage -> coverage.record(observation) }
            }
            policies.forEach { (policy, coverage) ->
                if (policy.shouldReject) {
                    assertThrows<AssertionError> { coverage.requireCovered(run) }
                } else {
                    coverage.requireCovered(run)
                }
            }
        }

    private fun withObservation(
        comparePermutation: Boolean = true,
        assertion: (GeneratedCaseObservation) -> Unit
    ): Unit =
        runBlocking {
            val suite = ResolverTestSuite.load(ResolverTestSuiteDefinition.CONSTRUCTION_WITNESS)
            suite.replay(selected = suite.testCaseIds().first()) { world, testCase ->
                assertion(observeGeneratedCase(world, testCase, captureSuppliedDemand = true, capturePermutationWitness = comparePermutation, comparePermutation = comparePermutation))
            }
        }
}
