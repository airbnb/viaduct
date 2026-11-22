package viaduct.engine.runtime2.resolvers.resolver23

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.contract.CheckerApplicationRecorder
import viaduct.engine.runtime2.contract.GeneratedResolutionObservation
import viaduct.engine.runtime2.contract.GeneratedTypeCheckerSignature
import viaduct.engine.runtime2.contract.requiredGeneratedTypeCheckerSignatures
import viaduct.engine.runtime2.contract.typeCheckerCoverage
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class GeneratedTypeCheckerCoverageTest {
    @Test
    fun `argument errors in type inputs are optional in every profile size`() {
        GeneratedTypeCheckerMode.entries.forEach { mode ->
            listOf(false, true).forEach { stressSized ->
                val required = requiredGeneratedTypeCheckerSignatures(mode, stressSized)
                assertFalse(GeneratedTypeCheckerSignature.ERROR_ARGUMENT_IN_TYPE_INPUT in required)
                assertTrue(GeneratedTypeCheckerSignature.TYPE_CHECKER in required)
                assertTrue(GeneratedTypeCheckerSignature.OBJECT_INPUT_RESOLVER in required)
                assertTrue(GeneratedTypeCheckerSignature.QUERY_INPUT_RESOLVER in required)
            }
        }
    }

    @Test
    fun `ordinary runtime profiles leave nested path activation to directed tests`() {
        val required = requiredGeneratedTypeCheckerSignatures(GeneratedTypeCheckerMode.RUNTIME_MIXED, stressSized = false)
        assertFalse(GeneratedTypeCheckerSignature.NESTED_PATH_VARIABLE in required)
        assertTrue(GeneratedTypeCheckerSignature.PATH_VARIABLE_IN_OBJECT_INPUT in required)
        assertTrue(GeneratedTypeCheckerSignature.PATH_VARIABLE_IN_QUERY_INPUT in required)
    }

    @Test
    fun `size-overridden runtime profiles require nested path activation`() {
        val required = requiredGeneratedTypeCheckerSignatures(GeneratedTypeCheckerMode.RUNTIME_MIXED, stressSized = true)
        assertTrue(GeneratedTypeCheckerSignature.NESTED_PATH_VARIABLE in required)
    }

    @Test
    fun `counts resolver backed inputs on both roots and checked dependencies beyond them`() {
        val observation = observation()
        val coverage = observation.typeCheckerCoverage()
        assertTrue(GeneratedTypeCheckerSignature.OBJECT_INPUT_RESOLVER in coverage)
        assertTrue(GeneratedTypeCheckerSignature.QUERY_INPUT_RESOLVER in coverage)
        assertTrue(GeneratedTypeCheckerSignature.RESOLVER_INPUT_FIELD_CHECK in coverage)
        assertTrue(GeneratedTypeCheckerSignature.RESOLVER_INPUT_TYPE_CHECK in coverage)
        assertTrue(GeneratedTypeCheckerSignature.SUCCESS in coverage)
        assertTrue(GeneratedTypeCheckerSignature.DENIAL in coverage)
        assertTrue(GeneratedTypeCheckerSignature.SUCCESS_WITH_RESOLVER_INPUT in coverage)
        assertFalse(GeneratedTypeCheckerSignature.DENIAL_WITH_RESOLVER_INPUT in coverage)
        assertTrue(GeneratedTypeCheckerSignature.FIELD_AND_TYPE_SAME_PATH in coverage)
    }

    @Test
    fun `registered dependencies without runtime invocations do not count as activation`() {
        val observation = observation().withResolverObserver(CorrectnessResolverObserver())
        assertNoResolverCoverage(observation)
    }

    @Test
    fun `same resolver paths on another Query root do not count as activation`() {
        val observation = observation()
        val recorder = CorrectnessResolverObserver()
        val original = observation.operation.resolutionObserver as CorrectnessResolverObserver
        val anotherRoot = observation().result
        original.allResolverInvocations().forEach { invocation ->
            recorder.onResolverInvocation(invocation.copy(resolverOccurrenceId = ResolverOccurrenceId.at(anotherRoot, invocation.occurrencePath)))
        }
        assertNoResolverCoverage(observation.withResolverObserver(recorder))
    }

    @Test
    fun `resolver evidence at another path does not count as activation`() {
        val observation = observation()
        val recorder = CorrectnessResolverObserver()
        val original = observation.operation.resolutionObserver as CorrectnessResolverObserver
        original.allResolverInvocations().forEach { invocation ->
            recorder.onResolverInvocation(
                invocation.copy(
                    resolverOccurrenceId = ResolverOccurrenceId.at(observation.result, invocation.occurrencePath + ListEngineResult.Index.of(7)),
                )
            )
        }
        assertNoResolverCoverage(observation.withResolverObserver(recorder))
    }

    private fun assertNoResolverCoverage(observation: GeneratedResolutionObservation) {
        val coverage = observation.typeCheckerCoverage()
        assertTrue(GeneratedTypeCheckerSignature.TYPE_CHECKER in coverage)
        assertFalse(GeneratedTypeCheckerSignature.OBJECT_INPUT_RESOLVER in coverage)
        assertFalse(GeneratedTypeCheckerSignature.QUERY_INPUT_RESOLVER in coverage)
        assertFalse(GeneratedTypeCheckerSignature.RESOLVER_INPUT_FIELD_CHECK in coverage)
        assertFalse(GeneratedTypeCheckerSignature.RESOLVER_INPUT_TYPE_CHECK in coverage)
    }

    private fun GeneratedResolutionObservation.withResolverObserver(observer: CorrectnessResolverObserver): GeneratedResolutionObservation =
        copy(
            operation = SharedOperationContext.create(
                world,
                resolutionObserver = observer.apply {
                    copyCheckerObservationsFrom(operation.resolutionObserver as CorrectnessResolverObserver)
                }
            )
        )

    private fun observation(): GeneratedResolutionObservation {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item! @resolver(script: "{'value': 1, 'dependency': {'value': 2}}") @checker(script: "allow")
              source: Dependency! @resolver(script: "{'value': 3}")
              probe: Int! @resolver(of: "source { value }", script: "5")
            }
            type Item @checker(inputs: [{name: "input", of: "active", qf: "probe"}], script: "allow") {
              value: Int!
              active: Int! @resolver(of: "dependency { value }", script: "4")
              dependency: Dependency!
            }
            type Dependency @checker(script: "deny") {
              value: Int! @checker(script: "allow")
            }
            """.trimIndent(),
        )
        val fragment = world.schemas.fragmentFrom("fragment Test on Query { item { value } }")
        val recorder = CheckerApplicationRecorder()
        val subject = ResolverGeneratedTest().observeResolution(world.newAssumptions(), world.schema.objectOf("Query"), fragment.subselections, resolutionObserver = recorder)
        return GeneratedResolutionObservation(subject.operation, fragment, subject, recorder.checkerApplications())
    }
}
