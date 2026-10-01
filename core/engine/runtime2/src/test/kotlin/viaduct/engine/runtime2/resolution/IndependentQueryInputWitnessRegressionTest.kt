package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.ResolutionOccurrenceApplicationLog
import viaduct.engine.runtime2.arbitrary.ResolutionOccurrenceWitness
import viaduct.engine.runtime2.contract.registeredResolverOccurrenceApplicationIdentityCounts
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver02.resolve as resolve02
import viaduct.engine.runtime2.resolvers.resolver23.resolve as resolve23

/** Independent reference inputs must not disappear into global Query-root deduplication. */
class IndependentQueryInputWitnessRegressionTest : ResolutionDispatcherResource {
    @Test
    fun `DFS witness rejects shared Query input across independent references`() = verify(2)

    @Test
    fun `grounded coroutine witness rejects shared Query input across independent references`() = verify(23)

    @Test
    fun `symbolic witness rejects shared Query input across independent references`() = verify(26)

    private fun verify(resolver: Int) {
        val world = TestWorld.fromSDL(
            selectiveResolvers = resolver != 2,
            schemaSDL = "type Query { first: Int!, second: Int!, target: Int!, source: Int! }",
            fieldResolvers = { schema ->
                val empty = schema.loweredSchema.emptyFragmentOf("Query")
                val target = schema.loweredSchema.requireObjectField("Query", "target")
                mapOf(
                    schema.loweredSchema.requireObjectField("Query", "first") to
                        fieldResolverOf(empty) { _, _ -> RootFieldReferenceData.of(listOf(target), emptyMap()) },
                    schema.loweredSchema.requireObjectField("Query", "second") to
                        fieldResolverOf(empty) { _, _ -> RootFieldReferenceData.of(listOf(target), emptyMap()) },
                    target to fieldResolverOf(
                        empty,
                        schema.fragmentFrom("fragment TargetInput on Query { source }"),
                    ) { _, query, _ -> query.outputValue("source") },
                    schema.loweredSchema.requireObjectField("Query", "source") to fieldResolverOf(empty) { _, _ -> 7 },
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
        val selections = world.schemas.fragmentFrom("fragment Test on Query { first second }").subselections
        val result = when (resolver) {
            2 -> operation.resolve02(selections)
            23 -> operation.resolve23(selections)
            else -> operation.resolveWithTestDispatcher(selections)
        }
        val requested = selections.merge(world.schema.requireQueryTypeDef())
        val witness = log.snapshot()
        assertEquals(6, witness.applications.size)
        assertTrue(result.correctResolution(operation, requested))
        assertEquals(result.registeredResolverOccurrenceApplicationIdentityCounts(operation), witness.applicationIdentityCounts())
        val associations = observer.allQueryFragmentResults()
        assertEquals(2, associations.size)
        val roots = associations.values.map { it.single() }
        assertTrue(roots[0] !== roots[1])
        val omittedSource = witness.applications.single {
            it.application.key.field == FieldCoordinate("Query", "source") &&
                it.resolverOccurrenceId == ResolverOccurrenceId.at(roots[1], it.occurrencePath)
        }
        val mutantWitness = ResolutionOccurrenceWitness(witness.applications.filter { it !== omittedSource })
        val mutantObserver = CorrectnessResolverObserver()
        events.filter { it.resolverOccurrenceId != omittedSource.resolverOccurrenceId }
            .forEach(mutantObserver::onResolverInvocation)
        observer.rootFieldReferenceInvocations().forEach(mutantObserver::onRootFieldReferenceInvocation)
        val scopes = observer.allQueryFragmentScopes()
        associations.forEach { (owner, _) ->
            val scope = scopes[owner]?.singleOrNull()
            if (scope == null) {
                mutantObserver.onIndependentQueryFragmentPrepared(owner, roots[0])
            } else {
                mutantObserver.onQueryFragmentPrepared(owner, roots[0], scope)
            }
        }
        observer.allQueryOERs().forEach { (root, context) ->
            if (root !== roots[1]) mutantObserver.onQueryOERPrepared(context)
        }
        val mutantOperation = SharedOperationContext.create(
            world.assumptions,
            variableBindings = operation.variableBindings,
            resolverObserver = mutantObserver,
        )
        assertEquals(5, mutantWitness.applications.size)
        // Either replay or occurrence accounting may reject. An explicit oracle rejection is valid.
        val accepted = try {
            result.correctResolution(mutantOperation, requested) &&
                result.registeredResolverOccurrenceApplicationIdentityCounts(mutantOperation) ==
                mutantWitness.applicationIdentityCounts()
        } catch (_: IllegalStateException) {
            false
        }
        assertFalse(accepted, "Independent reference inputs require 6 applications; both gates accepted the corrupted 5-application witness")
    }
}
