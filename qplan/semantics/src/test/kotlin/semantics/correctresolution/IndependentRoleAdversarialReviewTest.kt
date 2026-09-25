package semantics.correctresolution

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.engineObjectDataOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.merge
import model.outputValue
import model.registry.ResolutionExecutionContext
import model.requireObjectField
import model.requireQueryTypeDef
import model.selectionForestOf
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.arbitrary.FieldCoordinate
import semantics.arbitrary.ResolutionOccurrenceApplicationLog
import semantics.contract.registeredResolverOccurrenceApplicationIdentityCounts
import semantics.resolvers.resolver01.DepthFirstResolve
import semantics.resolvers.resolver02.resolve
import semantics.shared.OEROccurrence
import semantics.shared.fieldResolverCycleTask
import semantics.shared.ResolverInvocationObservation
import semantics.shared.SharedOERContext
import semantics.shared.SharedOperationContext
import semantics.shared.materializeResult

/** The final ownership gate must distinguish ordinary owners from justified reference targets. */
class IndependentRoleAdversarialReviewTest {
    @Test
    fun `ordinary shared execution remains accepted with exactly one source`() {
        val calls = AtomicInteger()
        val world = world(calls, references = false)
        val observer = RecordingObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val selections = world.assumptions.fragmentFrom("fragment Result on Query { left right }").subselections
        val result = operation.resolve(selections)
        assertEquals(1, calls.get())
        assertEquals(3, observer.log.snapshot().applications.size)
        assertTrue(observer.rootFieldReferenceInvocations().isEmpty())
        assertTrue(result.correctResolution(operation, selections.merge(world.schema.requireQueryTypeDef())))
        assertEquals(observer.log.snapshot().applicationIdentityCounts(), result.registeredResolverOccurrenceApplicationIdentityCounts(operation))
    }

    @Test
    fun `actual independent reference targets remain accepted with separate sources`() {
        val calls = AtomicInteger()
        val world = world(calls, references = true)
        val observer = RecordingObserver()
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
        val selections = world.assumptions.fragmentFrom("fragment Result on Query { left right }").subselections
        val result = operation.resolve(selections)
        assertEquals(2, calls.get())
        assertEquals(6, observer.log.snapshot().applications.size)
        assertEquals(2, observer.rootFieldReferenceInvocations().size)
        assertTrue(result.correctResolution(operation, selections.merge(world.schema.requireQueryTypeDef())))
        assertEquals(observer.log.snapshot().applicationIdentityCounts(), result.registeredResolverOccurrenceApplicationIdentityCounts(operation))
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
        observer.onQueryOERPrepared(SharedOERContext(
            OEROccurrence(unusedAssociatedRoot, emptyList(), unusedAssociatedRoot),
            engineObjectDataOf(queryType),
            selectionForestOf().merge(queryType),
        ), 1)
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
                observer.onResolverInvocation(ResolverInvocationObservation(
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
                ))
                resolver(
                    input = input,
                    queryValue = queryValue,
                    arguments = arguments,
                    selectiveResolvers = false,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            }
            result.setCellValue(key, value)
        }
        result.freeze()
        assertEquals(2, calls.get(), "The mutant really executes two source bodies")
        assertEquals(4, observer.log.snapshot().applications.size)
        assertTrue(observer.rootFieldReferenceInvocations().isEmpty(), "No source result contains a reference")
        assertEquals(2, observer.allQueryFragmentResults().values.map { it.single() }.toSet().size)
        val requested = world.assumptions.fragmentFrom("fragment Result on Query { left right }").subselections.merge(queryType)
        assertTrue(result.correctResolution(operation, requested), "All actual values and owner projections remain correct")
        val accepted = try {
            result.registeredResolverOccurrenceApplicationIdentityCounts(operation) ==
                observer.log.snapshot().applicationIdentityCounts()
        } catch (_: IllegalStateException) {
            false
        }
        assertFalse(accepted, "Independent-role owners require source-justified reference hops; ordinary owners cannot use the old four-application policy")
    }

    private fun world(calls: AtomicInteger, references: Boolean): TestWorld = TestWorld.fromSDL(
        selectiveResolvers = false,
        schemaSDL = "type Query { left: Int! right: Int! target: Int! source: Int! }",
        fieldResolvers = { schema ->
            val empty = schema.emptyFragmentOf("Query")
            val query = schema.fragmentFrom("fragment Input on Query { source }")
            val target = schema.requireObjectField("Query", "target")
            buildMap {
                listOf("left", "right").forEach { name ->
                    put(schema.requireObjectField("Query", name), if (references) {
                        fieldResolverOf(empty) { _, _ -> RootFieldReferenceData.of(listOf(target), emptyMap()) }
                    } else {
                        fieldResolverOf(empty, query) { _, value, _ -> value.outputValue("source") }
                    })
                }
                put(target, fieldResolverOf(empty, query) { _, value, _ -> value.outputValue("source") })
                put(schema.requireObjectField("Query", "source"), fieldResolverOf(empty) { _, _ -> calls.incrementAndGet(); 7 })
            }
        },
    )

    private class RecordingObserver : CorrectnessResolverObserver() {
        val log = ResolutionOccurrenceApplicationLog()

        override fun onResolverInvocation(observation: ResolverInvocationObservation) {
            super.onResolverInvocation(observation)
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
}
