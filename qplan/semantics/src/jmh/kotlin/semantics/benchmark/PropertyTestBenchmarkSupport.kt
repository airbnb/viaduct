@file:Suppress("ForbiddenImport")

package semantics.benchmark

import jdk.jfr.Category
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import kotlinx.coroutines.runBlocking
import model.Assumptions
import model.Fragment
import model.ObjectEngineResult
import model.fragmentFrom
import model.merge
import model.objectOf
import model.requireQueryTypeDef
import org.openjdk.jmh.infra.Blackhole
import semantics.arbitrary.ResolutionWitness
import semantics.arbitrary.ResolverBenchmarkCorpus
import semantics.contract.registeredResolverApplicationIdentityCounts
import semantics.contract.validateFromFieldBindings
import semantics.correctresolution.correctResolution
import semantics.shared.SharedOperationContext
import semantics.shared.instantiateBindings

internal const val DEFAULT_PROPERTY_TEST_LOOP_COUNT = 1

private const val PROPERTY_TEST_SCHEMA_RESOURCE =
    "semantics/benchmark/property-test/schema.graphqls"
private const val PROPERTY_TEST_REGISTRY_RESOURCE =
    "semantics/benchmark/property-test/registry.json"
private const val PROPERTY_TEST_QUERY_RESOURCE =
    "semantics/benchmark/property-test/query.graphql"
private const val RESOLVER_APPLICATIONS_METRIC = "resolverApplications"

@Name("qplan.PropertyTestPhase")
@Label("Property Test Phase")
@Category("QPlan")
private class PropertyTestPhaseEvent : Event() {
    @Label("Phase")
    lateinit var phase: String
}

/**
 * Runs the frozen Resolver26 property case through the same measured body as the broad campaign.
 */
internal class PropertyTestBenchmarkSupport(
    private val subject: ResolverBenchmarkSubject,
) {
    private lateinit var corpus: ResolverBenchmarkCorpus
    private lateinit var testWorld: model.testing.TestWorld
    private lateinit var querySource: String
    private var expectedResolverApplications: Int = 0

    fun prepareTrial() {
        corpus =
            ResolverBenchmarkCorpus.load(
                PROPERTY_TEST_SCHEMA_RESOURCE,
                PROPERTY_TEST_REGISTRY_RESOURCE,
            )
        testWorld = corpus.world()
        querySource =
            requireNotNull(
                javaClass.classLoader.getResourceAsStream(PROPERTY_TEST_QUERY_RESOURCE),
            ) {
                "Missing property-test benchmark query resource $PROPERTY_TEST_QUERY_RESOURCE"
            }.bufferedReader().use { reader -> reader.readText() }
        expectedResolverApplications =
            Math.toIntExact(
                requireNotNull(corpus.metrics[RESOLVER_APPLICATIONS_METRIC]) {
                    "Property-test benchmark corpus is missing $RESOLVER_APPLICATIONS_METRIC"
                },
            )
    }

    fun propertyTest(
        loopCount: Int,
        blackhole: Blackhole,
        profilePhases: Boolean = false,
    ): Int =
        runBlocking {
            require(loopCount > 0) {
                "Property-test benchmark loop count must be positive"
            }
            check(expectedResolverApplications > 0) {
                "Property-test benchmark trial was not prepared"
            }
            repeat(loopCount) {
                val preparationEvent =
                    profilePhaseEvent(profilePhases, "request preparation")
                val world: Assumptions
                val fragment: Fragment
                try {
                    world = testWorld.newAssumptions(selectiveResolvers = true)
                    fragment = testWorld.schemas.fragmentFrom(querySource)
                } finally {
                    preparationEvent?.finish()
                }
                val observer = corpus.registry.resolverObserver()
                val operation = SharedOperationContext.create(world, resolverObserver = observer)
                corpus.registry.clearResolutionWitness()
                val result: ObjectEngineResult =
                    profilePhase(profilePhases, "Resolver26") {
                        subject.resolve(
                            operation = operation,
                            root = world.objectOf("Query"),
                            selections = fragment.subselections,
                        )
                    }
                val witness: ResolutionWitness =
                    profilePhase(profilePhases, "witness snapshot") {
                        corpus.registry.resolutionWitness()
                    }
                check(witness.applications.size == expectedResolverApplications) {
                    "Expected $expectedResolverApplications resolver applications, " +
                        "observed ${witness.applications.size}"
                }
                profilePhase(profilePhases, "application identity oracle") {
                    check(
                        result.registeredResolverApplicationIdentityCounts(operation) == witness.applicationIdentityCounts(),
                    )
                }
                profilePhase(profilePhases, "correctResolution") {
                    check(
                        result.correctResolution(
                            operation,
                            fragment.subselections
                                .merge(world.schema.requireQueryTypeDef())
                                .instantiateBindings(operation),
                        ),
                    )
                }
                profilePhase(profilePhases, "from-field binding oracle") {
                    result.validateFromFieldBindings(operation, observer.invokedResolverOccurrences())
                }
                blackhole.consume(result)
            }
            loopCount
        }

    private fun profilePhaseEvent(
        enabled: Boolean,
        phase: String,
    ): PropertyTestPhaseEvent? =
        if (enabled) {
            PropertyTestPhaseEvent().apply {
                this.phase = phase
                begin()
            }
        } else {
            null
        }

    private inline fun <T> profilePhase(
        enabled: Boolean,
        phase: String,
        block: () -> T,
    ): T {
        if (!enabled) return block()
        val event = PropertyTestPhaseEvent().apply { this.phase = phase }
        event.begin()
        return try {
            block()
        } finally {
            event.end()
            event.commit()
        }
    }

    private fun PropertyTestPhaseEvent.finish() {
        end()
        commit()
    }
}
