@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.correctresolution

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.runtime2.arbitrary.BoundedRecorder
import viaduct.engine.runtime2.arbitrary.FieldCoordinate
import viaduct.engine.runtime2.arbitrary.ResolutionOccurrenceWitness
import viaduct.engine.runtime2.arbitrary.ResolverOccurrenceApplicationRecord
import viaduct.engine.runtime2.contract.registeredResolverOccurrenceApplicationIdentityCounts
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOERContext
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.materializeResult
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstResolve
import viaduct.engine.runtime2.resolvers.resolver02.resolve

/** The final ownership gate must distinguish ordinary owners from justified reference targets. */
class IndependentRoleAdversarialReviewTest {
    @Test
    fun `ordinary shared execution remains accepted with exactly one source`() {
        val calls = AtomicInteger()
        val world = world(calls, references = false)
        val observer = RecordingObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val selections = world.schemas.fragmentFrom("fragment Result on Query { left right }").subselections
        val result = operation.resolve(selections)
        assertEquals(1, calls.get())
        assertEquals(3, observer.log.snapshot().size)
        assertTrue(observer.rootFieldReferenceInvocations().isEmpty())
        assertTrue(result.correctResolution(operation, selections.merge(world.schema.requireQueryTypeDef())))
        assertEquals(ResolutionOccurrenceWitness(observer.log.snapshot()).applicationIdentityCounts(), result.registeredResolverOccurrenceApplicationIdentityCounts(operation))
    }

    @Test
    fun `actual independent reference targets remain accepted with separate sources`() {
        val calls = AtomicInteger()
        val world = world(calls, references = true)
        val observer = RecordingObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val selections = world.schemas.fragmentFrom("fragment Result on Query { left right }").subselections
        val result = operation.resolve(selections)
        assertEquals(2, calls.get())
        assertEquals(6, observer.log.snapshot().size)
        assertEquals(2, observer.rootFieldReferenceInvocations().size)
        assertTrue(result.correctResolution(operation, selections.merge(world.schema.requireQueryTypeDef())))
        assertEquals(ResolutionOccurrenceWitness(observer.log.snapshot()).applicationIdentityCounts(), result.registeredResolverOccurrenceApplicationIdentityCounts(operation))
    }

    @Test
    fun `old per-owner execution cannot pass as independent reference input`() {
        val calls = AtomicInteger()
        val world = world(calls, references = false)
        val observer = RecordingObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val queryType = world.schema.requireQueryTypeDef()
        val result = ObjectEngineResult.of(queryType, mutable = true)

        // Mutation: restore the former ordinary-owner call into the fresh-root helper that the
        // final runtime retains for references. The helper itself emits all of its real events;
        // this test neither relabels captured events nor fabricates source invocations.
        val unusedAssociatedRoot = ObjectEngineResult.of(queryType)
        observer.onQueryOERPrepared(
            SharedOERContext(
                OEROccurrence(unusedAssociatedRoot, emptyList(), unusedAssociatedRoot),
                engineObjectDataOf(queryType),
                selectionForestOf().merge(queryType),
            ),
            1
        )
        listOf("left", "right").forEach { name ->
            val field = world.schema.requireObjectField("Query", name)
            val key = ObjectEngineResult.GroundKey.of(field, emptyMap())
            val path = listOf(key)
            val owner = ResolverOccurrenceId.at(result, path)
            val resolver = world.assumptions.resolverRegistry.resolver(field)
            val fragments = resolver.instantiateFragmentsAt(result, path)
            val queryResult = DepthFirstResolve(operation) { it }.resolve(
                fragments.queryFragment.constructionSelections,
                queryFragmentOwner = owner,
            )
            val value = runBlocking {
                val queryValue = queryResult.materializeResult(
                    operation,
                    resolver.instantiateQueryMaterializationSelections(owner),
                    result.fieldResolverCycleTask(path),
                )
                val input = engineObjectDataOf(queryType)
                val arguments = key.arguments as Arguments.Resolved
                observer.onResolverInvocation(
                    ResolverInvocationObservation(
                        occurrencePath = path,
                        field = field,
                        input = input,
                        inputSelections = materializeSelectionForestOf(),
                        queryValue = queryValue,
                        queryInputSelections =
                            resolver.instantiateQueryMaterializationSelections(owner),
                        arguments = arguments,
                        suppliedDemand = null,
                        resolverOccurrenceId = owner,
                    )
                )
                resolver(
                    input = input,
                    queryValue = queryValue,
                    arguments = arguments,
                    selectiveResolvers = false,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            }
            result.reserveCell(key).apply {
                this.value.set(value)
                fieldCheckerResult.complete(null)
            }
        }
        result.freeze()
        assertEquals(2, calls.get(), "The mutant really executes two source bodies")
        assertEquals(4, observer.log.snapshot().size)
        assertTrue(observer.rootFieldReferenceInvocations().isEmpty(), "No source result contains a reference")
        assertEquals(2, observer.allQueryFragmentResults().values.map { it.single() }.toSet().size)
        val requested = world.schemas.fragmentFrom("fragment Result on Query { left right }").subselections.merge(queryType)
        assertTrue(result.correctResolution(operation, requested), "All actual values and owner projections remain correct")
        val accepted = try {
            result.registeredResolverOccurrenceApplicationIdentityCounts(operation) ==
                ResolutionOccurrenceWitness(observer.log.snapshot()).applicationIdentityCounts()
        } catch (_: IllegalStateException) {
            false
        }
        assertFalse(accepted, "Independent-role owners require source-justified reference hops; ordinary owners cannot use the old four-application policy")
    }

    private fun world(
        calls: AtomicInteger,
        references: Boolean
    ): TestWorld =
        TestWorld.fromSDL(
            selectiveResolvers = false,
            schemaSDL = "type Query { left: Int! right: Int! target: Int! source: Int! }",
            fieldResolvers = { schema ->
                val empty = schema.loweredSchema.emptyFragmentOf("Query")
                val query = schema.fragmentFrom("fragment Input on Query { source }")
                val target = schema.loweredSchema.requireObjectField("Query", "target")
                buildMap {
                    listOf("left", "right").forEach { name ->
                        put(
                            schema.loweredSchema.requireObjectField("Query", name),
                            if (references) {
                                fieldResolverOf(empty) { _, _ -> RootFieldReferenceData.of(listOf(target), emptyMap()) }
                            } else {
                                fieldResolverOf(empty, query) { _, value, _ -> value.outputValue("source") }
                            }
                        )
                    }
                    put(target, fieldResolverOf(empty, query) { _, value, _ -> value.outputValue("source") })
                    put(
                        schema.loweredSchema.requireObjectField("Query", "source"),
                        fieldResolverOf(empty) { _, _ ->
                            calls.incrementAndGet()
                            7
                        }
                    )
                }
            },
        )

    private class RecordingObserver : CorrectnessResolverObserver() {
        val log = BoundedRecorder<ResolverOccurrenceApplicationRecord>()

        override fun onResolverInvocation(observation: ResolverInvocationObservation) {
            super.onResolverInvocation(observation)
            log.record(
                ResolverOccurrenceApplicationRecord.capture(
                    resolverOccurrenceId = observation.resolverOccurrenceId,
                    occurrencePath = observation.occurrencePath,
                    field = FieldCoordinate(observation.field.containingDef.name, observation.field.name),
                    arguments = observation.arguments,
                    input = observation.input,
                    suppliedDemand = observation.suppliedDemand,
                ),
            )
        }
    }
}
