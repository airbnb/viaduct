@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.contract.registeredResolverApplicationIdentityCounts
import viaduct.engine.runtime2.contract.validateFromFieldBindings
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.conformsToResolvers
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.propertytest.ResolverTestSuite
import viaduct.engine.runtime2.propertytest.ResolverTestSuiteDefinition
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.instantiateBindings

class FullBenchmarkReplayTest : ResolutionDispatcherResource {
    @Test
    fun `checker-only Query roots participate in full benchmark application reconstruction`(): Unit =
        runBlocking {
            val suite = ResolverTestSuite.load(ResolverTestSuiteDefinition.RESOLVER_BENCHMARK_FULL_CHECKER_ROOTS)
            val run = suite.replay(suite.testCaseIds().first()) { fixture, testCase ->
                val world = fixture.newAssumptions()
                val checkerEvidence = CorrectnessResolverObserver()
                val resolutionObserver = object : CorrectnessResolverObserver(testCase.registry.resolutionObserver()) {
                    override val onCheckerInvocation = checkerEvidence.onCheckerInvocation
                    override val onCheckerQueryFragmentPrepared = checkerEvidence.onCheckerQueryFragmentPrepared
                }
                val operation = SharedOperationContext.create(world, resolutionObserver = resolutionObserver)
                val fragment = fixture.schemas.fragmentFrom(testCase.query.source)
                testCase.registry.clearResolutionWitness()
                val result = operation.resolveWithTestDispatcher(fragment.subselections)
                val observed = testCase.registry.resolutionWitness().applicationIdentityCounts()
                assertTrue(checkerEvidence.hasCheckerInputs())
                val resolverRoots = resolutionObserver.allQueryFragmentResults().values.flatten()
                val checkerRoots = checkerEvidence.allCheckerQueryFragmentResults().values.flatten()
                assertTrue(checkerRoots.any { checkerRoot -> resolverRoots.none { it === checkerRoot } })

                assertFalse(result.conformsToResolvers(operation), "Dropping checker evidence reproduces the benchmark failure")
                resolutionObserver.copyCheckerObservationsFrom(checkerEvidence)
                assertTrue(result.conformsToResolvers(operation))
                assertEquals(observed, result.registeredResolverApplicationIdentityCounts(operation))
                assertTrue(
                    result.correctResolution(
                        operation,
                        fragment.subselections.merge(world.schema.requireQueryTypeDef()).instantiateBindings(operation),
                    ),
                )
                result.validateFromFieldBindings(operation, resolutionObserver.invokedResolverOccurrences())
            }
            assertEquals(1, run.attemptedCases)
        }
}
