package viaduct.engine.runtime2.resolution.recording

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.IdentityHashMap
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.ArgumentResolutionError
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.BackingDataEngineResult
import viaduct.engine.runtime2.model.Conjunction
import viaduct.engine.runtime2.model.Disjunction
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.IDEngineResult
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.JSONEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.fieldExpressions
import viaduct.engine.runtime2.model.nodeReferenceIdentityOrNull
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.render
import viaduct.engine.runtime2.resolution.FieldResolverOccurrence
import viaduct.engine.runtime2.resolution.FieldResolverTask
import viaduct.engine.runtime2.resolution.RootFieldReferenceOccurrence
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolutionTask
import viaduct.engine.runtime2.resolution.framework.ResolutionTaskState
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.RootFieldReferenceInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedFieldResolverTask
import viaduct.engine.runtime2.resolution.framework.SharedOrchestrationTask
import viaduct.graphql.schema.ViaductSchema

/** Snapshots callbacks under one lock, then forwards each callback outside the lock. */
class RecordingResolutionObserver(
    private val test: RecordedTest,
    private val delegate: ResolutionObserver = ResolutionObserver.NOP,
    initialDemand: SelectionForest? = null,
    private val source: RecordedResolutionSource? = null,
) : ResolutionObserver {
    private val initialDemand = initialDemand?.let { mapper.valueToTree<JsonNode>(selections(it)) }
    private val lock = Any()
    private val taskIds = IdentityHashMap<ResolutionTask, Int>()
    private val oerIds = IdentityHashMap<ObjectEngineResult, Int>()
    private val occurrenceIds = mutableMapOf<ResolverOccurrenceId, Int>()
    private val tasks = mutableListOf<RecordedTask>()
    private val oers = mutableListOf<ObjectEngineResult>()
    private val events = mutableListOf<RecordedObservation>()
    private var completed = false
    private var failure: RecordedFailure? = null

    override val onTaskPrepared: (ResolutionTask) -> Unit = { task ->
        observe("TASK_PREPARED") {
            taskFacts(task) + if (task is SharedOrchestrationTask<*>) {
                mapOf(
                    "constructionDemand" to selections(task.objectOER.closedValueSelections),
                    "queryConstructionDemand" to selections(task.queryOER.closedValueSelections),
                )
            } else {
                emptyMap()
            }
        }
        delegate.onTaskPrepared?.invoke(task)
    }

    override val onTaskExecution: (ResolutionTask, ResolutionTaskState, Throwable?) -> Unit = { task, state, cause ->
        observe("TASK_EXECUTION") { taskFacts(task) + mapOf("state" to state.name, "cause" to cause?.recorded()) }
        delegate.onTaskExecution?.invoke(task, state, cause)
    }

    override val onQueryOERPrepared: (SharedOrchestrationTask<*>, Int?) -> Unit = { task, depth ->
        observe("QUERY_OER_PREPARED") {
            mapOf(
                "taskId" to taskId(task),
                "object" to location(task.objectOER.occurrence),
                "query" to location(task.queryOER.occurrence),
                "depth" to depth,
                "oer" to oerState(task.queryOER.occurrence.target)
            )
        }
        delegate.onQueryOERPrepared?.invoke(task, depth)
    }

    override val onQueryFragmentPrepared: (ResolverOccurrenceId, ObjectEngineResult, OEROccurrence?) -> Unit = { owner, result, scope ->
        observe("QUERY_FRAGMENT_PREPARED") {
            mapOf("resolverOccurrenceId" to occurrenceId(owner), "oerId" to oerId(result), "owner" to scope?.let(::location), "oer" to oerState(result))
        }
        delegate.onQueryFragmentPrepared?.invoke(owner, result, scope)
    }

    override val onIndependentQueryFragmentPrepared: (ResolverOccurrenceId, ObjectEngineResult) -> Unit = { owner, result ->
        observe("INDEPENDENT_QUERY_FRAGMENT_PREPARED") {
            mapOf("resolverOccurrenceId" to occurrenceId(owner), "oerId" to oerId(result), "oer" to oerState(result))
        }
        delegate.onIndependentQueryFragmentPrepared?.invoke(owner, result)
    }

    override val onQueryFragmentOwnerAddress: (ResolverOccurrenceId, OEROccurrence, ObjectEngineResult.ObjectKey) -> Unit = { owner, occurrence, key ->
        observe("QUERY_FRAGMENT_OWNER_ADDRESS") {
            mapOf("resolverOccurrenceId" to occurrenceId(owner), "owner" to location(occurrence), "key" to key(key))
        }
        delegate.onQueryFragmentOwnerAddress?.invoke(owner, occurrence, key)
    }

    override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
        observe("RESOLVER_INVOCATION") {
            mapOf(
                "resolverOccurrenceId" to occurrenceId(observation.resolverOccurrenceId),
                "path" to path(observation.occurrencePath),
                "field" to coordinate(observation.field),
                "arguments" to value(observation.arguments),
                "input" to value(observation.input),
                "inputSelections" to materializeSelections(observation.inputSelections),
                "queryInput" to value(observation.queryValue),
                "queryInputSelections" to materializeSelections(observation.queryInputSelections),
                "suppliedDemand" to observation.suppliedDemand?.let(::selections),
            )
        }
        delegate.onResolverInvocation?.invoke(observation)
    }

    override val onRootFieldReferenceInvocation: (RootFieldReferenceInvocationObservation) -> Unit = { observation ->
        observe("ROOT_FIELD_REFERENCE_INVOCATION") {
            mapOf(
                "publication" to location(observation.publicationRoot, observation.publicationPath),
                "invocation" to location(observation.invocationRoot, observation.invocationPath),
                "resolverOccurrenceId" to occurrenceId(ResolverOccurrenceId.at(observation.invocationRoot, observation.invocationPath)),
                "key" to key(observation.invocationKey),
                "reference" to reference(observation.reference),
                "suppliedDemand" to selections(observation.suppliedDemand),
            )
        }
        delegate.onRootFieldReferenceInvocation?.invoke(observation)
    }

    override val onCheckerInvocation: (CheckerInvocationObservation, Map<String, CheckerInput>) -> Unit = { observation, inputs ->
        observe("CHECKER_INVOCATION") {
            mapOf(
                "checkerKind" to observation.checkerKind.name,
                "target" to observation.checkedTarget.render(),
                "location" to location(observation.logicalQueryRoot, observation.occurrencePath),
                "arguments" to observation.arguments?.let(::value),
                "inputs" to inputs.mapValues { (_, input) -> mapOf("object" to value(input.objectValue), "query" to value(input.queryValue)) },
            )
        }
        delegate.onCheckerInvocation?.invoke(observation, inputs)
    }

    override val onCheckerQueryFragmentPrepared: (ResolverTarget, ResolverOccurrenceId, ObjectEngineResult) -> Unit = { target, owner, result ->
        observe("CHECKER_QUERY_FRAGMENT_PREPARED") {
            mapOf("target" to target.render(), "resolverOccurrenceId" to occurrenceId(owner), "oerId" to oerId(result), "oer" to oerState(result))
        }
        delegate.onCheckerQueryFragmentPrepared?.invoke(target, owner, result)
    }

    fun complete(result: ObjectEngineResult) {
        observe("RESOLUTION_COMPLETED") {
            val rootId = oerId(result)
            val states = mutableListOf<Any>()
            var index = 0
            while (index < oers.size) states += oerState(oers[index++])
            completed = true
            mapOf("rootId" to rootId, "oers" to states)
        }
    }

    fun failed(cause: Exception) {
        synchronized(lock) { failure = cause.recorded() }
    }

    fun snapshot(): ResolutionRecording =
        synchronized(lock) {
            ResolutionRecording(
                test = test,
                tasks = tasks.map { it.copy(location = it.location?.deepCopy<JsonNode>()) },
                oers = oers.mapIndexed { index, oer -> RecordedOER(index + 1, oer.type.name) },
                events = events.map { it.copy(facts = it.facts.deepCopy<JsonNode>()) },
                completed = completed,
                failure = failure,
                initialDemand = initialDemand?.deepCopy<JsonNode>(),
                source = source,
            )
        }

    private fun observe(
        kind: String,
        facts: () -> Map<String, Any?>
    ) {
        synchronized(lock) {
            events += RecordedObservation(events.size.toLong() + 1, kind, mapper.valueToTree(facts()))
        }
    }

    private fun taskId(task: ResolutionTask): Int {
        taskIds[task]?.let { return it }
        val creatorId = task.creator?.let(::taskId)
        val id = tasks.size + 1
        taskIds[task] = id
        val occurrence = when (task) {
            is SharedOrchestrationTask<*> -> task.objectOER.occurrence
            is MutationOrchestrationTask<*> -> task.namespace.occurrence
            is SharedFieldResolverTask<*> -> task.publication.oerOccurrence
            else -> null
        }
        val source = (task as? FieldResolverTask)?.publication?.sourceOccurrence
        val address = if (occurrence != null) {
            location(occurrence.root, source?.publicationPath ?: occurrence.path, occurrence.target)
        } else {
            null
        }
        val resolverId = (source as? FieldResolverOccurrence)?.resolverOccurrenceId?.let(::occurrenceId)
        val kind = when (task) {
            is SharedOrchestrationTask<*> -> "ORCHESTRATION"
            is MutationOrchestrationTask<*> -> "MUTATION_ORCHESTRATION"
            is SharedFieldResolverTask<*> -> "FIELD_RESOLVER"
            else -> task.javaClass.simpleName.ifEmpty { task.javaClass.name }
        }
        tasks += RecordedTask(id, kind, creatorId, address?.let { mapper.valueToTree(it) }, resolverId)
        return id
    }

    private fun taskFacts(task: ResolutionTask): Map<String, Any?> {
        val id = taskId(task)
        val occurrence = when (task) {
            is SharedOrchestrationTask<*> -> task.objectOER.occurrence
            is MutationOrchestrationTask<*> -> task.namespace.occurrence
            is SharedFieldResolverTask<*> -> task.publication.oerOccurrence
            else -> null
        }
        val reference = ((task as? FieldResolverTask)?.publication?.sourceOccurrence as? RootFieldReferenceOccurrence)?.reference
        return mapOf("taskId" to id, "oer" to occurrence?.let { oerState(it.target) }) +
            if (reference != null) mapOf("sourceReference" to reference(reference)) else emptyMap()
    }

    private fun occurrenceId(id: ResolverOccurrenceId): Int = occurrenceIds.getOrPut(id) { occurrenceIds.size + 1 }

    private fun oerId(oer: ObjectEngineResult): Int =
        oerIds[oer] ?: (oers.size + 1).also {
            oerIds[oer] = it
            oers += oer
        }

    private fun location(occurrence: OEROccurrence): Map<String, Any?> = location(occurrence.root, occurrence.path, occurrence.target)

    private fun location(
        root: ObjectEngineResult,
        path: List<PathComponent>,
        target: ObjectEngineResult? = null
    ): Map<String, Any?> = mapOf("rootId" to oerId(root), "path" to path(path), "oerId" to target?.let(::oerId))

    private fun path(path: List<PathComponent>): List<Any> =
        path.map { component ->
            when (component) {
                is ObjectEngineResult.ObjectKey -> key(component)
                is ListEngineResult.Index -> mapOf("index" to component.index)
            }
        }

    private fun coordinate(field: ViaductSchema.Field): String = "${field.containingDef.name}.${field.name}"

    private fun reference(reference: RootFieldReferenceData): Map<String, Any?> =
        mapOf(
            "path" to reference.path.map(::coordinate),
            "arguments" to value(reference.arguments),
            "nodeIdentity" to reference.nodeReferenceIdentityOrNull()?.let { mapOf("type" to it.type.name, "id" to it.id) },
        )

    private fun key(key: ObjectEngineResult.Key): Map<String, Any?> =
        mapOf("field" to coordinate(key.field), "arguments" to value(key.arguments), "responseKey" to (key as? ObjectEngineResult.MutationKey)?.responseKey)

    private fun selections(forest: SelectionForest): List<Any> =
        buildList {
            forest.forEach { selection ->
                add(
                    mapOf(
                        "key" to key(selection.key),
                        "types" to selection.possibleTypes.map {
                            it.name
                        }.sorted(),
                        "condition" to condition(selection.inclusionCondition),
                        "children" to selections(selection.subselections)
                    )
                )
            }
        }

    private fun materializeSelections(forest: MaterializeSelectionForest): List<Any> =
        buildList {
            forest.forEach { selection ->
                add(
                    mapOf(
                        "key" to key(selection.key),
                        "responseKey" to selection.responseKey,
                        "types" to selection.possibleTypes.map {
                            it.name
                        }.sorted(),
                        "condition" to condition(selection.inclusionCondition),
                        "children" to materializeSelections(selection.subselections)
                    )
                )
            }
        }

    private fun condition(condition: InclusionCondition): Any =
        when (condition) {
            InclusionCondition.Always -> true
            InclusionCondition.Never -> false
            is InclusionCondition.Requires -> condition.values.map { (variable, required) -> mapOf("variable" to value(variable), "required" to required) }
            is Conjunction -> mapOf("and" to listOf(condition(condition.left), condition(condition.right)))
            is Disjunction -> mapOf("or" to listOf(condition(condition.left), condition(condition.right)))
        }

    private fun oerState(oer: ObjectEngineResult): Map<String, Any?> =
        mapOf(
            "id" to oerId(oer),
            "fields" to oer.keys.map { key ->
                val promise = oer.getCell(key).value
                if (!promise.isCompleted) {
                    mapOf("key" to key(key), "state" to "PENDING")
                } else {
                    try {
                        mapOf("key" to key(key), "state" to "COMPLETED", "value" to value(promise.get()))
                    } catch (cause: Exception) {
                        mapOf("key" to key(key), "state" to "FAILED", "cause" to cause.recorded())
                    }
                }
            },
        )

    private fun value(
        value: Any?,
        seen: IdentityHashMap<Any, Int> = IdentityHashMap()
    ): Any? =
        when (value) {
            null, is String, is Boolean, is Int, is Long -> value
            is Double -> if (value.isFinite()) value else mapOf("kind" to "number", "type" to value.javaClass.name, "value" to value.toString())
            is Number -> mapOf("kind" to "number", "type" to value.javaClass.name, "value" to value.toString())
            is Arguments.Variable -> mapOf(
                "kind" to "variable",
                "name" to value.variableName,
                "target" to value.target.render(),
                "owner" to value.instanceId?.resolverOccurrenceId?.let(::occurrenceId)
            )
            Arguments.Error, ArgumentResolutionError -> mapOf("kind" to "argumentError")
            is Arguments -> value.fieldExpressions().mapValues { (_, element) -> value(element, seen) }
            is ObjectEngineResult -> mapOf("kind" to "oer", "id" to oerId(value))
            is ErrorEngineResult -> mapOf("kind" to "error", "cause" to value.errorData.cause?.recorded())
            is EngineErrorData -> mapOf("kind" to "error", "cause" to value.cause?.recorded())
            is IDEngineResult -> mapOf("kind" to "id", "value" to value.value)
            is JSONEngineResult -> mapOf("kind" to "json", "value" to value(value.value, seen))
            is BackingDataEngineResult -> mapOf("kind" to "opaque", "type" to value.value.javaClass.name)
            is ViaductSchema.EnumValue -> mapOf("kind" to "enum", "value" to value.name)
            is EngineObjectData.Sync, is Map<*, *>, is List<*> -> {
                val previous = seen[value]
                if (previous != null) {
                    mapOf("kind" to "reference", "id" to previous)
                } else {
                    val id = seen.size + 1
                    seen[value] = id
                    when (value) {
                        is EngineObjectData.Sync -> mapOf(
                            "kind" to "object",
                            "id" to id,
                            "type" to value.type.name,
                            "fields" to value.getSelections().associateWith { value(value.outputValue(it), seen) }
                        )
                        is Map<*, *> -> mapOf("kind" to "map", "id" to id, "fields" to value.entries.associate { (key, element) -> key.toString() to value(element, seen) })
                        else -> mapOf(
                            "kind" to "list",
                            "id" to id,
                            "values" to (value as List<*>).map { element ->
                                if (element is EngineResultCell) {
                                    if (!element.value.isCompleted) {
                                        mapOf("kind" to "pending")
                                    } else {
                                        try {
                                            value(element.value.get(), seen)
                                        } catch (cause: Exception) {
                                            mapOf("kind" to "failed", "cause" to cause.recorded())
                                        }
                                    }
                                } else {
                                    value(element, seen)
                                }
                            }
                        )
                    }
                }
            }
            else -> mapOf("kind" to "opaque", "type" to value.javaClass.name)
        }

    private fun Throwable.recorded(): RecordedFailure = RecordedFailure(javaClass.name, message)

    companion object {
        private val mapper = jacksonObjectMapper()
    }
}
