package viaduct.engine.runtime2.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import viaduct.engine.runtime2.arbitrary.GeneratedFieldCheckerMode
import viaduct.engine.runtime2.arbitrary.GeneratedTypeCheckerMode
import viaduct.engine.runtime2.arbitrary.PrecomputedResolverOutputs
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkCorpus
import viaduct.engine.runtime2.arbitrary.ResolverBenchmarkQueryCorpus
import viaduct.engine.runtime2.arbitrary.resolutionFingerprint
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.materializeResult
import viaduct.engine.runtime2.schema.lowering.LOWERED_TYPENAME_FIELD

class ChinaActiveBenchmarkCheckersTest : ResolutionDispatcherResource {
    private val resourcePrefix = "viaduct/engine/runtime2/benchmark/current-profile/"
    private val loader = object : ClassLoader(ChinaActiveBenchmarkCheckersTest::class.java.classLoader) {
        override fun getResourceAsStream(name: String) = super.getResourceAsStream(name) ?: Files.newInputStream(Path.of("src/jmh/resources", name))
    }
    private val corpus = ResolverBenchmarkCorpus.load(resourcePrefix + "schema.graphqls", resourcePrefix + "registry.json.gz", loader)
    private val profile = ChinaActiveBenchmarkCheckers(corpus)
    private val fixture = ResolverBenchmarkFixture(corpus)

    private fun world(outputs: PrecomputedResolverOutputs): TestWorld = fixture.world(outputs)

    @Test
    fun `field policies include passive fields and distinguish zero slots from null composites`() {
        val world = world(PrecomputedResolverOutputs())
        val checkers = profile.fieldPolicies.map { (coordinate, _) ->
            world.resolverRegistry.fieldChecker(world.schema.requireObjectField(coordinate.typeName, coordinate.fieldName))!!
        }
        assertTrue(profile.fieldPolicies.keys.any { it.typeName != "Query" && it !in corpus.registry.fieldResolverCoordinates })
        assertTrue(checkers.any { it.fragmentTemplates.isEmpty() })
        assertTrue(checkers.any { it.fragmentTemplates.size == 3 })
        val namedNull = checkers.flatMap { it.fragmentTemplates.entries }.first { it.key.endsWith("chinaNull") }.value
        assertTrue(namedNull.objectFragmentTemplate.isEmpty())
        assertTrue(namedNull.queryFragmentTemplate.isEmpty())
        assertTrue(checkers.all { it.fragmentTemplates.size <= 3 })
        assertTrue(profile.typePolicies.values.flatten().none { it.startsWith("china") })
        assertTrue(profile.typePolicies.keys.all { it.startsWith("Object") })
        val ordinary = corpus.world(fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_SUCCESS)
        corpus.registry.fieldResolverCoordinates.forEach { coordinate ->
            val checker = ordinary.resolverRegistry.fieldChecker(ordinary.schema.requireObjectField(coordinate.typeName, coordinate.fieldName))!!
            assertEquals(setOf("left", "right", "empty", "rawObject", "rawQuery"), checker.fragmentTemplates.keys)
        }
    }

    @Test
    fun `China has a guarded object ID and deeper Query in one slot with an active provider`() =
        runTest {
            val world = world(PrecomputedResolverOutputs())
            profile.fieldPolicies.filterValues { "chinaQuery" in it }.forEach { (coordinate, _) ->
                val field = world.schema.requireObjectField(coordinate.typeName, coordinate.fieldName)
                val checker = world.resolverRegistry.fieldChecker(field)!!
                val china = checker.fragmentTemplates.entries.single { it.key.endsWith("chinaQuery") }.value
                assertEquals("policyId", china.objectFragmentTemplate.single().responseKey)
                assertTrue(!china.queryFragmentTemplate.filter { !it.subselections.isEmpty() }.isEmpty())
                assertEquals(1, china.variables.values.count { it is VariableDefinition.FromField })
                assertEquals(listOf("policyId"), (china.variables.values.first { it is VariableDefinition.FromField } as VariableDefinition.FromField).responsePath)
                if (field.args.isEmpty()) assertEquals(mapOf("shouldSkipForChinaAccess" to false), china.variablesProvider!!(Arguments.Resolved.of(field, emptyMap())))
            }
            val providers = profile.fieldResolvers(world.schemas)
            val field = world.schema.requireObjectField("Query", "benchmarkChinaObject0")
            val resolver = world.resolverRegistry.resolver(field)

            suspend fun invoke() =
                resolver(
                    input = world.schema.objectOf("Query"),
                    arguments = Arguments.Resolved.of(field, mapOf("id" to null)),
                    selectiveResolvers = false,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            assertSame(invoke(), invoke())
            assertEquals(14, providers.size)
        }

    @Test
    fun `all fixed queries materialize China inputs using actual object IDs in fresh registries`() {
        val queries = ResolverBenchmarkQueryCorpus.load(resourcePrefix + "queries.json", loader).querySources
        val discovery = PrecomputedResolverOutputs()
        val discoveryWorld = world(discovery)
        queries.forEach { source ->
            SharedOperationContext.create(discoveryWorld.assumptions).resolveWithTestDispatcher(discoveryWorld.schemas.fragmentFrom(source).subselections)
        }
        val prepared = discovery.prepared()
        val world = world(prepared)
        assertNotSame(discoveryWorld.resolverRegistry, world.resolverRegistry)
        val chinaInputs = ConcurrentLinkedQueue<Pair<String, Any?>>()
        val lookupArguments = ConcurrentLinkedQueue<Pair<String, Any?>>()
        queries.forEach { source ->
            val operation = SharedOperationContext.create(
                world.newAssumptions(),
                resolverObserver = object : ResolverObserver {
                    override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                        if (observation.field.name.startsWith("benchmarkChinaObject")) {
                            lookupArguments += observation.field.name.removePrefix("benchmarkChina") to observation.arguments.fieldValues.getValue("id")
                        }
                    }
                },
                checkerObserver = object : CheckerObserver {
                    override fun onCheckerInvocation(observation: CheckerInvocationObservation) = Unit

                    override fun onCheckerInvocation(
                        observation: CheckerInvocationObservation,
                        inputs: Map<String, CheckerInput>
                    ) {
                        inputs.filterKeys { it.endsWith("chinaQuery") }.values.forEach { input ->
                            val typeName = (observation.checkedTarget as ResolverTarget.FieldCheckerTarget).field.containingDef.name
                            assertTrue(input.queryValue.isPresent("benchmarkChina$typeName"))
                            assertTrue(input.queryValue.isPresent(LOWERED_TYPENAME_FIELD))
                            chinaInputs += typeName to input.objectValue.get("policyId")
                        }
                    }
                },
            )
            operation.resolveWithTestDispatcher(world.schemas.fragmentFrom(source).subselections)
        }
        assertTrue(chinaInputs.size > 100)
        assertTrue(lookupArguments.toSet().containsAll(chinaInputs.toSet()))
    }

    @Test
    fun `replacing hidden checker demand preserves bulk client selected values`() =
        runTest(timeout = 5.minutes) {
            val queries = ResolverBenchmarkQueryCorpus.load(resourcePrefix + "queries.json", loader).querySources.mapIndexed { index, source ->
                if (index < ResolverBenchmarkFeaturePartition.selections.size * 3) source.substringBeforeLast("\n  coverage") + "\n}" else source
            }

            fun oldWorld(outputs: PrecomputedResolverOutputs) =
                corpus.registry.world(
                    schemaSDL = corpus.schemaSDL,
                    fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_SUCCESS,
                    typeCheckerMode = GeneratedTypeCheckerMode.RUNTIME_SUCCESS,
                    precomputedResolverOutputs = outputs,
                )

            suspend fun selectedValue(
                world: TestWorld,
                source: String
            ): viaduct.engine.runtime2.arbitrary.ResolutionFingerprint {
                val fragment = world.schemas.fragmentFrom(source)
                val operation = SharedOperationContext.create(world.newAssumptions())
                val result = operation.resolveWithTestDispatcher(fragment.subselections)
                return result.materializeResult(operation, fragment.materializeSelections, result.fieldResolverCycleTask(emptyList())).resolutionFingerprint()
            }
            val oldDiscovery = PrecomputedResolverOutputs()
            val newDiscovery = PrecomputedResolverOutputs()
            val old = oldWorld(oldDiscovery)
            val new = world(newDiscovery)
            queries.forEach { source -> assertEquals(selectedValue(old, source), selectedValue(new, source), source) }
            val preparedOld = oldWorld(oldDiscovery.prepared())
            val preparedNew = world(newDiscovery.prepared())
            queries.forEach { source -> assertEquals(selectedValue(preparedOld, source), selectedValue(preparedNew, source), source) }
        }
}
