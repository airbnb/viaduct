package viaduct.engine.runtime2.resolution.recording

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.ArgumentResolutionError
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.JSONEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolutionTask
import viaduct.engine.runtime2.resolution.framework.ResolutionTaskState
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class RecordingResolutionObserverTest : ResolutionDispatcherResource {
    @Test
    fun `real resolution records task ancestry OER growth and invocation facts`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { viewer: User! @resolver(result: {first: 2, last: 3}) }
            type User {
              first: Int!
              last: Int!
              display: Int! @resolver(of: "first last", result: "sum(first, last)")
              greeting: Int! @resolver(of: "display", result: "sumplus1(display)")
            }
            """.trimIndent(),
        )
        val delegate = CorrectnessResolverObserver()
        val selections = world.schemas.operationSelectionsFrom("{ viewer { greeting } }")
        val recorder = RecordingResolutionObserver(RecordedTest("Example", "greeting"), delegate, selections)
        val operation = SharedOperationContext.create(world.assumptions, resolutionObserver = recorder)
        recorder.complete(operation.resolveWithTestDispatcher(selections))

        val recording = recorder.snapshot()
        assertTrue(recording.completed)
        assertNull(recording.failure)
        assertEquals(5, recording.tasks.size)
        val root = recording.tasks.single { it.creatorId == null }
        assertEquals("ORCHESTRATION", root.kind)
        val viewer = recording.tasks.single { it.creatorId == root.id }
        assertEquals("FIELD_RESOLVER", viewer.kind)
        val user = recording.tasks.single { it.creatorId == viewer.id }
        assertEquals("ORCHESTRATION", user.kind)
        assertEquals(2, recording.tasks.count { it.creatorId == user.id })
        assertEquals(3, delegate.invokedResolverOccurrences().size)
        assertEquals(3, recording.events.count { it.kind == "RESOLVER_INVOCATION" })
        assertTrue(recording.tasks.filter { it.kind == "FIELD_RESOLVER" }.all { it.resolverOccurrenceId != null })
        assertEquals(listOf("Query", "User"), recording.oers.map { it.type }.distinct().sorted())
        val finalState = recording.events.last().facts
        val userState = finalState["oers"].single { state -> state["fields"].any { it["key"]["field"].asText() == "User.greeting" } }
        assertEquals(6, userState["fields"].single { it["key"]["field"].asText() == "User.greeting" }["value"].asInt())
        val firstPreparation = recording.events.first { it.kind == "TASK_PREPARED" }
        assertTrue(firstPreparation.facts["oer"]["fields"].any { it["state"].asText() == "PENDING" })
        val initialDemand = requireNotNull(recording.initialDemand)
        assertEquals("Query.viewer", initialDemand.single()["key"]["field"].asText())
        assertEquals(listOf("User.greeting"), initialDemand.single()["children"].map { it["key"]["field"].asText() })
        val userPreparation = recording.events.single { it.kind == "TASK_PREPARED" && it.facts["taskId"].asInt() == user.id }
        assertEquals(
            setOf("User.greeting", "User.display", "User.first", "User.last"),
            userPreparation.facts["constructionDemand"].map { it["key"]["field"].asText() }.toSet(),
        )
        assertEquals(0, userPreparation.facts["queryConstructionDemand"].size())
        val mapper = jacksonObjectMapper()
        assertEquals(recording, mapper.readValue<ResolutionRecording>(mapper.writeValueAsBytes(recording)))
        (initialDemand.single() as ObjectNode).removeAll()
        assertTrue(recorder.snapshot().initialDemand!!.single().has("key"))
    }

    @Test
    fun `decorators forward independent ownership and checker callbacks exactly once`() {
        val world = TestWorld.fromSDL("type Query { value: Int }").assumptions
        val type = world.schema.requireQueryTypeDef()
        val field = world.schema.requireObjectField("Query", "value")
        val root = ObjectEngineResult.of(type, mutable = true)
        val other = ObjectEngineResult.of(type, mutable = true)
        val owner = ResolverOccurrenceId.at(root, emptyList())
        val scope = OEROccurrence(root, emptyList(), root)
        val key = ObjectEngineResult.GroundKey.of(field, emptyMap())
        val target = ResolverTarget.FieldCheckerTarget(field)
        val inputs = mapOf("input" to CheckerInput(engineObjectDataOf(type), engineObjectDataOf(type)))
        val calls = mutableListOf<String>()
        val delegate = object : ResolutionObserver {
            override val onQueryFragmentPrepared: (ResolverOccurrenceId, ObjectEngineResult, OEROccurrence?) -> Unit = { _, _, _ -> calls += "shared" }
            override val onIndependentQueryFragmentPrepared: (ResolverOccurrenceId, ObjectEngineResult) -> Unit = { _, _ -> calls += "independent" }
            override val onQueryFragmentOwnerAddress: (ResolverOccurrenceId, OEROccurrence, ObjectEngineResult.ObjectKey) -> Unit = { _, _, _ -> calls += "owner" }
            override val onCheckerQueryFragmentPrepared: (ResolverTarget, ResolverOccurrenceId, ObjectEngineResult) -> Unit = { _, _, _ -> calls += "checkerQuery" }
            override val onCheckerInvocation: (CheckerInvocationObservation, Map<String, CheckerInput>) -> Unit = { _, _ -> calls += "checker" }
        }
        val recorder = RecordingResolutionObserver(RecordedTest("Example", "forwarding"), CorrectnessResolverObserver(delegate))
        val outer = CorrectnessResolverObserver(recorder)
        outer.onQueryFragmentPrepared(owner, root, scope)
        outer.onIndependentQueryFragmentPrepared(owner, other)
        outer.onQueryFragmentOwnerAddress(owner, scope, key)
        outer.onCheckerQueryFragmentPrepared(target, owner, other)
        outer.onCheckerInvocation(CheckerInvocationObservation(CheckerKind.FIELD, root, emptyList(), null, target), inputs)

        assertEquals(listOf("shared", "independent", "owner", "checkerQuery", "checker"), calls)
        val recording = recorder.snapshot()
        assertEquals(5, recording.events.size)
        assertEquals(2, recording.oers.size)
        assertNotEquals(recording.events[0].facts["oerId"], recording.events[1].facts["oerId"])
        assertEquals(recording.events[1].facts["oerId"], recording.events[3].facts["oerId"])
        assertEquals(listOf(1), recording.events.take(4).map { it.facts["resolverOccurrenceId"].asInt() }.distinct())
    }

    @Test
    fun `invocation inputs are copied before delegate mutation and opaque values are not serialized`() {
        val world = TestWorld.fromSDL("scalar JSON type Query { value: Int payload: JSON }")
        val type = world.schema.requireQueryTypeDef()
        val field = world.schema.requireObjectField("Query", "value")
        val root = ObjectEngineResult.of(type)
        val elements = mutableListOf<Any?>(1, null)
        val payload = linkedMapOf<String, Any?>(
            "elements" to elements,
            "opaque" to object {
                fun getSecret(): String = error("Must not serialize opaque objects")

                override fun toString(): String = error("Must not stringify opaque objects")
            }
        )
        payload["cycle"] = payload
        payload["notFinite"] = Double.NaN
        payload["argumentError"] = ArgumentResolutionError
        val input = engineObjectDataOf(type, mapOf("payload" to payload))
        val selections = world.assumptions.emptyFragmentOf("Query").materializeSelections
        val observation = ResolverInvocationObservation(
            emptyList(),
            field,
            input,
            selections,
            engineObjectDataOf(type),
            selections,
            Arguments.Resolved.of(field, emptyMap()),
            null,
            ResolverOccurrenceId.at(root, emptyList()),
        )
        val recorder = RecordingResolutionObserver(
            RecordedTest("Example", "snapshot"),
            object : ResolutionObserver {
                override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = {
                    elements[0] = 99
                }
            }
        )
        recorder.onResolverInvocation(observation)
        val facts = recorder.snapshot().events.single().facts
        val recorded = facts["input"]["fields"]["payload"]["fields"]
        assertEquals(1, recorded["elements"]["values"][0].asInt())
        assertTrue(recorded["elements"]["values"][1].isNull)
        assertEquals("opaque", recorded["opaque"]["kind"].asText())
        assertEquals("reference", recorded["cycle"]["kind"].asText())
        assertEquals("NaN", recorded["notFinite"]["value"].asText())
        assertEquals("argumentError", recorded["argumentError"]["kind"].asText())
        val mapper = jacksonObjectMapper()
        assertEquals(recorder.snapshot(), mapper.readValue<ResolutionRecording>(mapper.writeValueAsBytes(recorder.snapshot())))
        (facts as ObjectNode).removeAll()
        assertTrue(recorder.snapshot().events.single().facts.has("input"))
    }

    @Test
    fun `pending failed null and JSON cells can be snapshotted without waiting`() {
        val world = TestWorld.fromSDL("scalar JSON type Query { pending: Int failed: Int absent: Int payload: JSON values: [Int] }")

        fun key(name: String) = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", name), emptyMap())
        val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
        root.reserveCell(key("pending"))
        root.reserveCell(key("failed")).value.freeze { IllegalStateException("failed") }
        root.setCellValue(key("absent"), null)
        root.setCellValue(key("payload"), JSONEngineResult.of(mapOf("answer" to 42)))
        val list = ListEngineResult.ofPendingValues(world.schema.requireObjectField("Query", "values").outputType.unwrapList()!!, 2)
        list[0].value.freeze { IllegalStateException("failed") }
        root.setCellValue(key("values"), list)
        val recorder = RecordingResolutionObserver(RecordedTest("Example", "pending"))
        recorder.complete(root)
        val fields = recorder.snapshot().events.single().facts["oers"].single()["fields"].associateBy { it["key"]["field"].asText() }
        assertEquals("PENDING", fields.getValue("Query.pending")["state"].asText())
        assertEquals("FAILED", fields.getValue("Query.failed")["state"].asText())
        assertTrue(fields.getValue("Query.absent")["value"].isNull)
        assertEquals("json", fields.getValue("Query.payload")["value"]["kind"].asText())
        assertEquals(listOf("failed", "pending"), fields.getValue("Query.values")["value"]["values"].map { it["kind"].asText() })
    }

    @Test
    fun `concurrent callbacks retain every event reuse task IDs and allow repeated finishes`() {
        val parent = object : ResolutionTask {
            override val creator: ResolutionTask? = null
        }
        val child = object : ResolutionTask {
            override val creator: ResolutionTask = parent
        }
        val recorder = RecordingResolutionObserver(RecordedTest("Example", "parallel"))
        val executor = Executors.newFixedThreadPool(8)
        try {
            executor.invokeAll(
                (1..100).map {
                    Callable { recorder.onTaskExecution(child, ResolutionTaskState.FINISHED, IllegalStateException("failure")) }
                }
            ).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        val recording = recorder.snapshot()
        assertEquals(2, recording.tasks.size)
        assertEquals(recording.tasks[0].id, recording.tasks[1].creatorId)
        assertEquals((1L..100L).toList(), recording.events.map { it.sequence })
        assertEquals(setOf(recording.tasks[1].id), recording.events.map { it.facts["taskId"].asInt() }.toSet())
        assertTrue(recording.events.all { it.facts["cause"]["message"].asText() == "failure" })
        assertFalse(recording.completed)
    }
}
