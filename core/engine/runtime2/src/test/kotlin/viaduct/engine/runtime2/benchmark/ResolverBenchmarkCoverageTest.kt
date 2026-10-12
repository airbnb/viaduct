package viaduct.engine.runtime2.benchmark

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import viaduct.engine.runtime2.arbitrary.PrecomputedResolverOutputs
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkQueryCorpus
import viaduct.engine.runtime2.contract.registeredResolverApplicationIdentityCounts
import viaduct.engine.runtime2.contract.validateFromFieldBindings
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.isParentField
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.instantiateBindings
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.engine.runtime2.schema.lowering.LOWERED_TYPENAME_FIELD

class ResolverBenchmarkCoverageTest : ResolutionDispatcherResource {
    private val prefix = "viaduct/engine/runtime2/benchmark/current-profile/"
    private val loader = object : ClassLoader(ResolverBenchmarkCoverageTest::class.java.classLoader) {
        override fun getResourceAsStream(name: String) = super.getResourceAsStream(name) ?: Files.newInputStream(Path.of("src/jmh/resources", name))
    }
    private val corpus = ResolverBenchmarkCorpus.load(prefix + "schema.graphqls", prefix + "registry.json.gz", loader)
    private val fixture = ResolverBenchmarkFixture(corpus)

    @Test
    fun `each feature section agrees with independent correctness and application judgments`() {
        val world = fixture.world(PrecomputedResolverOutputs())
        ResolverBenchmarkFeaturePartition.selections.forEach { selection ->
            corpus.registry.clearResolutionWitness()
            val observer = corpus.registry.resolverObserver()
            val operation = SharedOperationContext.create(world.newAssumptions(), resolverObserver = observer, checkerObserver = CorrectnessCheckerObserver())
            val selections = world.schemas.fragmentFrom("fragment Result on Query { $selection }").subselections
            val result = operation.resolveWithTestDispatcher(selections)
            assertTrue(result.correctResolution(operation, selections.merge(world.schema.requireQueryTypeDef()).instantiateBindings(operation)), selection)
            val expected = result.registeredResolverApplicationIdentityCounts(operation).filterKeys { identity ->
                !("coverageSupplied" in selection && identity.key.field.toString() in setOf("CoverageRoot/branch", "CoverageBranch/middle", "CoverageMiddle/leaf")) &&
                    !("coverageReferences" in selection && identity.key.field.toString() == "CoverageReferences/object")
            }
            assertEquals(expected, corpus.registry.resolutionWitness().applicationIdentityCounts(), selection)
            result.validateFromFieldBindings(operation, observer.invokedResolverOccurrences())
        }
    }

    @Test
    fun `registrations and repeated observations of one query cannot satisfy coverage`() {
        val world = fixture.world(PrecomputedResolverOutputs())
        val coverage = ResolverBenchmarkCoverage()
        assertFailsWith<IllegalStateException> { coverage.assertComplete() }
        repeat(3) {
            val observer = corpus.registry.resolverObserver()
            val operation = SharedOperationContext.create(world.newAssumptions(), resolverObserver = observer, checkerObserver = CorrectnessCheckerObserver())
            val selections = world.schemas.fragmentFrom("fragment Result on Query { ${ResolverBenchmarkFeaturePartition.selections.first()} }").subselections
            coverage.record(0, operation, operation.resolveWithTestDispatcher(selections), emptyList())
        }
        val error = assertFailsWith<IllegalStateException> { coverage.assertComplete() }
        assertTrue("PARENT=1" in error.message.orEmpty())
        assertTrue("FIELD_CHECK_DENIAL=0" in error.message.orEmpty())
    }

    @Test
    fun `feature object growth retains two active and fourteen passive fields`() {
        val world = fixture.world(PrecomputedResolverOutputs())
        val types = world.schema.types.values.filterIsInstance<viaduct.graphql.schema.ViaductSchema.Object>().filter { it.name.startsWith("Coverage") }
        assertEquals(10, types.size)
        types.forEach { type ->
            assertEquals(2, type.fields.count { it.name != LOWERED_TYPENAME_FIELD && !it.isParentField() && it in world.resolverRegistry }, type.name)
            assertEquals(14, type.fields.count { (it.isParentField() || it !in world.resolverRegistry) && !it.name.startsWith("__") && it.name != LOWERED_TYPENAME_FIELD }, type.name)
        }
    }

    @Test
    fun `result statistics omit excluded cells and stop at parent backedges`() =
        runTest {
            val world = fixture.world(PrecomputedResolverOutputs())
            val excluded = SharedOperationContext.create(world.newAssumptions())
            val excludedResult = excluded.resolveWithTestDispatcher(world.schemas.fragmentFrom("fragment Result on Query { ${ResolverBenchmarkFeaturePartition.selections.last()} }").subselections)
            assertEquals(ResolverBenchmarkResultShape(fields = 1, activeFields = 1, depth = 1), excludedResult.benchmarkShape(excluded.world))
            val parents = SharedOperationContext.create(world.newAssumptions())
            val parentResult = parents.resolveWithTestDispatcher(world.schemas.fragmentFrom("fragment Result on Query { ${ResolverBenchmarkFeaturePartition.selections.first()} }").subselections)
            val parentShape = parentResult.benchmarkShape(parents.world)
            assertTrue(parentShape.fields > 1)
            assertTrue(parentShape.depth in 4..6)
        }

    @Test
    fun `the frozen corpus has a bounded query count and feature additions are reproducible`() {
        val sources = ResolverBenchmarkQueryCorpus.load(prefix + "queries.json", loader).querySources
        assertTrue(sources.size in 100..250)
        assertEquals(sources.size, sources.distinct().size)
        assertEquals(ResolverBenchmarkFeaturePartition.selections.size * 3, sources.count { "coverage" in it })
    }

    @Test
    fun `the prepared frozen corpus activates every required feature in at least three queries`() =
        runTest {
            val sources = ResolverBenchmarkQueryCorpus.load(prefix + "queries.json", loader).querySources
            val discovery = PrecomputedResolverOutputs()
            val discoveryWorld = fixture.world(discovery)
            sources.forEach { source ->
                SharedOperationContext.create(discoveryWorld.newAssumptions()).resolveWithTestDispatcher(discoveryWorld.schemas.fragmentFrom(source).subselections)
            }
            val world = fixture.world(discovery.prepared())
            val coverage = ResolverBenchmarkCoverage()
            sources.forEachIndexed { index, source ->
                val applications = java.util.concurrent.CopyOnWriteArrayList<CheckerInvocationObservation>()
                val observer = corpus.registry.resolverObserver()
                corpus.registry.clearResolutionWitness()
                val operation = SharedOperationContext.create(world.newAssumptions(), resolverObserver = observer, checkerObserver = CorrectnessCheckerObserver(CheckerObserver { applications += it }))
                val selections = world.schemas.fragmentFrom(source).subselections
                val result = operation.resolveWithTestDispatcher(selections)
                result.validateFromFieldBindings(operation, observer.invokedResolverOccurrences())
                val shape = result.benchmarkShape(operation.world)
                assertTrue(shape.fields > 0, "query $index")
                coverage.record(index, operation, result, applications)
            }
            println(coverage.report())
            coverage.assertComplete()
        }
}
