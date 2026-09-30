package semantics.resolver26

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.outputValue
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolutionOccurrenceApplicationLog
import semantics.arbitrary.ResolutionOccurrenceWitness
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.shared.ResolverInvocationObservation
import semantics.shared.SharedOperationContext

/** Scope mutation: corrupt the associations and trace together, as a request-global cache would. */
class SharedQueryCrossScopeWitnessRegressionTest : Resolver26DispatcherResource {
    @Test
    fun `same containing scope still permits a single shared producer`() {
        val run = execute(sameScope = true)
        assertEquals(4, run.witness.applications.size)
        assertEquals(1, run.sources.size)
        assertEquals(2, run.observer.allQueryFragmentResults().size)
        assertEquals(1, run.observer.allQueryFragmentResults().values.flatten().toSet().size)
        assertTrue(run.result.correctResolution(run.operation, run.selections))
        assertEquals(
            run.result.registeredResolverOccurrenceApplicationIdentityCounts(run.operation),
            run.witness.applicationIdentityCounts(),
        )
    }

    @Test
    fun `merging both Query associations and producer witness across list scopes is rejected`() {
        val run = execute(sameScope = false)
        assertEquals(5, run.witness.applications.size)
        assertEquals(2, run.sources.size)
        assertTrue(run.result.correctResolution(run.operation, run.selections))
        assertEquals(
            run.result.registeredResolverOccurrenceApplicationIdentityCounts(run.operation),
            run.witness.applicationIdentityCounts(),
        )
        val associations = run.observer.allQueryFragmentResults()
        val owners = associations.keys.toList()
        assertEquals(2, owners.size)
        val ownerPaths = owners.map { owner -> run.events.single { it.resolverOccurrenceId == owner }.occurrencePath }
        assertNotEquals(ownerPaths[0].dropLast(1), ownerPaths[1].dropLast(1))
        val roots = associations.values.map { it.single() }
        val sharedRoot = roots[0]
        val omittedRoot = roots[1]
        assertTrue(sharedRoot !== omittedRoot)
        val omittedSource = run.sources.single { it.resolverOccurrenceId == ResolverOccurrenceId.at(omittedRoot, it.occurrencePath) }
        val mutantWitness = ResolutionOccurrenceWitness(
            run.witness.applications.filter { it !== omittedSource },
        )
        val mutantObserver = CorrectnessResolverObserver()
        run.events.filter { it.resolverOccurrenceId != omittedSource.resolverOccurrenceId }
            .forEach(mutantObserver::onResolverInvocation)
        val scopes = run.observer.allQueryFragmentScopes()
        associations.forEach { (owner, _) ->
            mutantObserver.onQueryFragmentPrepared(
                owner,
                sharedRoot,
                scopes.getValue(owner).single(),
            )
        }
        run.observer.allQueryOERs().forEach { (root, context) ->
            if (root !== omittedRoot) mutantObserver.onQueryOERPrepared(context)
        }
        val mutantOperation = SharedOperationContext.create(
            run.operation.world,
            variableBindings = run.operation.variableBindings,
            resolverObserver = mutantObserver,
        )
        assertEquals(4, mutantWitness.applications.size)
        // Extensional replay is intentionally permissive; occurrence accounting owns this gate.
        assertTrue(run.result.correctResolution(mutantOperation, run.selections))
        assertFailsWith<IllegalStateException> {
            run.result.registeredResolverOccurrenceApplicationIdentityCounts(mutantOperation)
        }
    }

    private fun execute(sameScope: Boolean): Run {
        val world = TestWorld.fromSDL(
            selectiveResolvers = true,
            schemaSDL = """
                type Query { items: [Payload!]!, source: Int! }
                type Payload { computed: Int!, sibling: Int! }
            """.trimIndent(),
            fieldResolvers = { schema ->
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "items") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            List(if (sameScope) 1 else 2) { schema.loweredSchema.objectOf("Payload") {} }
                        },
                    schema.loweredSchema.requireObjectField("Query", "source") to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                    schema.loweredSchema.requireObjectField("Payload", "computed") to
                        fieldResolverOf(
                            objectFragment = schema.loweredSchema.emptyFragmentOf("Payload"),
                            queryFragment = schema.fragmentFrom("fragment Input on Query { source }"),
                        ) { _, query, _ -> query.outputValue("source") },
                    schema.loweredSchema.requireObjectField("Payload", "sibling") to
                        fieldResolverOf(
                            objectFragment = schema.loweredSchema.emptyFragmentOf("Payload"),
                            queryFragment = schema.fragmentFrom("fragment Input on Query { source }"),
                        ) { _, query, _ -> query.outputValue("source") },
                )
            },
        )
        val log = ResolutionOccurrenceApplicationLog()
        val events = ConcurrentLinkedQueue<ResolverInvocationObservation>()
        val observer = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                events.add(observation)
                log.record(
                    resolverOccurrenceId = observation.resolverOccurrenceId,
                    occurrencePath = observation.occurrencePath,
                    field = FieldCoordinate(observation.field.containingDef.name, observation.field.name),
                    arguments = observation.arguments,
                    input = observation.input,
                    suppliedDemand = observation.suppliedDemand,
                )
            }
        }
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val selections = world.schemas.fragmentFrom(
            if (sameScope) {
                "fragment Test on Query { items { computed sibling } }"
            } else {
                "fragment Test on Query { items { computed } }"
            },
        ).subselections.merge(world.assumptions.schema.requireQueryTypeDef())
        val result = operation.resolveWithTestDispatcher(selections)
        return Run(operation, observer, result, selections, log.snapshot(), events.toList())
    }

    private data class Run(
        val operation: SharedOperationContext<*>,
        val observer: CorrectnessResolverObserver,
        val result: ObjectEngineResult,
        val selections: model.ObjectSelectionForest,
        val witness: ResolutionOccurrenceWitness,
        val events: List<ResolverInvocationObservation>,
    ) {
        val sources get() = witness.applications.filter {
            it.application.key.field == FieldCoordinate("Query", "source")
        }
    }
}
