package viaduct.engine.runtime2.correctresolution

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.toCanonicalMaterializeSelectionForest
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation

class CorrectnessResolverObserverTest {
    @Test
    fun `unsubscribed callbacks are null and skip observation construction`() {
        val observer = ResolutionObserver.NOP
        assertNull(observer.onQueryOERPrepared)
        assertNull(observer.onQueryFragmentPrepared)
        assertNull(observer.onIndependentQueryFragmentPrepared)
        assertNull(observer.onQueryFragmentOwnerAddress)
        assertNull(observer.onResolverInvocation)
        assertNull(observer.onRootFieldReferenceInvocation)
        assertNull(observer.onCheckerInvocation)
        assertNull(observer.onCheckerQueryFragmentPrepared)
        var constructed = false

        fun <T> createObservation(): T {
            constructed = true
            error("An unsubscribed observation must not be constructed")
        }
        observer.onResolverInvocation?.invoke(createObservation())
        observer.onCheckerInvocation?.invoke(createObservation(), createObservation())
        observer.onCheckerQueryFragmentPrepared?.invoke(createObservation(), createObservation(), createObservation())
        assertFalse(constructed)
    }

    @Test
    fun `combined recorder retains checker targets duplicates and input snapshots and forwards once`() {
        val world = TestWorld.fromSDL("type Query { value: Int }").assumptions
        val type = world.schema.requireQueryTypeDef()
        val field = world.schema.requireObjectField("Query", "value")
        val root = ObjectEngineResult.of(type, mutable = true)
        val query = ObjectEngineResult.of(type, mutable = true)
        val path = listOf(ObjectEngineResult.GroundKey.of(field, emptyMap()))
        val owner = ResolverOccurrenceId.at(root, path)
        val input = CheckerInput(engineObjectDataOf(type), engineObjectDataOf(type))
        val inputs = mutableMapOf("input" to input)
        val fieldTarget = ResolverTarget.FieldCheckerTarget(field)
        val typeTarget = ResolverTarget.TypeCheckerTarget(type)
        val fieldCall = CheckerInvocationObservation(CheckerKind.FIELD, root, path, Arguments.Resolved.of(field, emptyMap()), fieldTarget)
        val typeCall = CheckerInvocationObservation(CheckerKind.TYPE, root, path, null, typeTarget)
        val calls = mutableListOf<CheckerInvocationObservation>()
        val preparations = mutableListOf<Triple<ResolverTarget, ResolverOccurrenceId, ObjectEngineResult>>()
        val recorder = CorrectnessResolverObserver(object : ResolutionObserver {
            override val onCheckerInvocation: (CheckerInvocationObservation, Map<String, CheckerInput>) -> Unit = { observation, actual ->
                calls += observation
                assertSame(inputs, actual)
            }
            override val onCheckerQueryFragmentPrepared: (ResolverTarget, ResolverOccurrenceId, ObjectEngineResult) -> Unit = { target, id, result ->
                preparations += Triple(target, id, result)
            }
        })
        assertFalse(recorder.hasCheckerInputs())

        recorder.onQueryFragmentPrepared(owner, root, null)
        listOf(fieldCall, typeCall, fieldCall).forEach { observation ->
            recorder.onCheckerQueryFragmentPrepared(observation.checkedTarget, owner, query)
            recorder.onCheckerInvocation(observation, inputs)
        }
        inputs.clear()

        assertEquals(listOf(fieldCall, typeCall, fieldCall), calls)
        assertEquals(listOf(fieldTarget, typeTarget, fieldTarget).map { Triple(it, owner, query) }, preparations)
        assertTrue(recorder.hasCheckerInputs())
        assertEquals(listOf(mapOf("input" to input), mapOf("input" to input)), recorder.checkerInputs(fieldTarget, owner))
        assertEquals(listOf(mapOf("input" to input)), recorder.checkerInputs(typeTarget, owner))
        assertEquals(mapOf((fieldTarget to owner) to listOf(query, query), (typeTarget to owner) to listOf(query)), recorder.allCheckerQueryFragmentResults())
        assertEquals(listOf(query, query), recorder.queryFragmentResults(fieldTarget, owner))
        assertEquals(listOf(root), recorder.queryFragmentResults(owner))

        val copy = CorrectnessResolverObserver()
        copy.copyCheckerObservationsFrom(recorder)
        assertEquals(recorder.allCheckerQueryFragmentResults(), copy.allCheckerQueryFragmentResults())
        assertEquals(recorder.checkerInputs(fieldTarget, owner), copy.checkerInputs(fieldTarget, owner))
        assertEquals(recorder.checkerInputs(typeTarget, owner), copy.checkerInputs(typeTarget, owner))
        assertTrue(copy.allQueryFragmentResults().isEmpty())
    }

    @Test
    fun `independent preparation defaults to the subscribed general callback`() {
        val type = TestWorld.fromSDL("type Query { value: Int }").assumptions.schema.requireQueryTypeDef()
        val result = ObjectEngineResult.of(type, mutable = true)
        val owner = ResolverOccurrenceId.at(result, emptyList())
        val seen = mutableListOf<Triple<ResolverOccurrenceId, ObjectEngineResult, OEROccurrence?>>()
        val observer: ResolutionObserver = object : ResolutionObserver {
            override val onQueryFragmentPrepared: (ResolverOccurrenceId, ObjectEngineResult, OEROccurrence?) -> Unit = { id, root, scope ->
                seen += Triple(id, root, scope)
            }
        }
        observer.onIndependentQueryFragmentPrepared?.invoke(owner, result)
        assertEquals(1, seen.size)
        assertEquals(owner, seen.single().first)
        assertSame(result, seen.single().second)
        assertNull(seen.single().third)
    }

    @Test
    fun `query preparation forwards canonical ownership once through the recorder`() {
        val world = TestWorld.fromSDL("type Query { value: Int }").assumptions
        val type = world.schema.requireQueryTypeDef()
        val root = ObjectEngineResult.of(type, mutable = true)
        val query = ObjectEngineResult.of(type, mutable = true)
        val scope = OEROccurrence(root, emptyList(), root)
        val owner = ResolverOccurrenceId.at(root, emptyList())
        val seen = mutableListOf<Triple<ResolverOccurrenceId, ObjectEngineResult, OEROccurrence?>>()
        val delegate = object : ResolutionObserver {
            override val onQueryFragmentPrepared: (ResolverOccurrenceId, ObjectEngineResult, OEROccurrence?) -> Unit = { id, result, occurrence ->
                seen += Triple(id, result, occurrence)
            }
        }
        val recorder = CorrectnessResolverObserver(delegate)
        recorder.onQueryFragmentPrepared(owner, query, scope)
        recorder.onIndependentQueryFragmentPrepared(owner, query)
        assertEquals(2, seen.size)
        assertEquals(listOf(owner, owner), seen.map { it.first })
        assertSame(query, seen.first().second)
        assertSame(scope, seen.first().third)
        assertNull(seen.last().third)
        assertEquals(listOf(query, query), recorder.queryFragmentResults(owner))
        assertEquals(listOf(scope), recorder.allQueryFragmentScopes().getValue(owner))
    }

    @Test
    fun `invocation identities deduplicate repeated observations`() {
        val worldFixture = TestWorld.fromSDL("type Query { first: Int second: Int }")
        val world = worldFixture.assumptions
        val root = ObjectEngineResult.of(type = world.schema.requireQueryTypeDef(), mutable = true)
        val observations = listOf("first", "second").map { name ->
            val field = world.schema.requireObjectField("Query", name)
            val path = listOf(ObjectEngineResult.GroundKey.of(field, emptyMap()))
            ResolverInvocationObservation(
                occurrencePath = path,
                field = field,
                input = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                inputSelections = world.emptyFragmentOf("Query").subselections.toCanonicalMaterializeSelectionForest(),
                queryValue = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                queryInputSelections = world.emptyFragmentOf("Query").materializeSelections,
                arguments = Arguments.Resolved.of(field, emptyMap()),
                suppliedDemand = null,
                resolverOccurrenceId = ResolverOccurrenceId.at(root, path),
            )
        }
        val observer = CorrectnessResolverObserver()
        observations.forEach(observer.onResolverInvocation)
        val expectedIds = observations.map { it.resolverOccurrenceId }.toSet()
        assertEquals(2, expectedIds.size)
        assertEquals(expectedIds, observer.invokedResolverOccurrences())

        // A new event and ID instance for the same occurrence must still deduplicate.
        observer.onResolverInvocation(
            observations.last().copy(
                resolverOccurrenceId = ResolverOccurrenceId.at(root, observations.last().occurrencePath),
            )
        )
        assertEquals(expectedIds, observer.invokedResolverOccurrences())
    }
}
