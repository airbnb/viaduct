package viaduct.engine.runtime2.resolvers.resolver06

import java.util.PriorityQueue
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.MutationNamespaceOccurrence
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.GroundedFieldPublicationOccurrence
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstDispatcher
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstFieldResolverTask
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstMutationTask
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstOperationContext
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstOrchestrationTask
import viaduct.engine.runtime2.resolvers.resolver01.DepthFirstTask

/** Queues the same tasks as Resolver01-03, using depth, task kind, and insertion order for readiness. */
internal class DepthFirstReactor(
    operation: SharedOperationContext<*>,
    complete: (SelectionForest) -> SelectionForest,
    private val source: EngineObjectData.Sync,
    private val selections: SelectionForest,
    private val onTaskStarted: (DepthFirstTask) -> Unit = {},
) : DepthFirstDispatcher {
    private val operation = DepthFirstOperationContext(operation, complete, this)
    private val tasks = PriorityQueue(depthFirstTaskComparator)
    private val dispatchedTasks = mutableSetOf<DepthFirstTask>()
    private val finished = mutableSetOf<DepthFirstTask>()
    private val orchestrated = mutableSetOf<OEROccurrence>()
    private val children = mutableMapOf<OEROccurrence, MutableList<DepthFirstOrchestrationTask>>()
    private var nextSequence = 0L
    private var started = false

    /** Constructs this reactor's result. May be called exactly once. */
    fun resolve(): ObjectEngineResult {
        check(!started) { "DepthFirstReactor.resolve() may only be called once" }
        started = true
        val result: ObjectEngineResult
        if (selections is MutationSelectionForest) {
            require(selections.type == operation.world.schema.mutationTypeDef) { "Mutations must start at their root" }
            result = MutationObjectEngineResult.of(selections)
            val task = DepthFirstMutationTask(operation, MutationNamespaceOccurrence(OEROccurrence(result, emptyList(), result), selections))
            dispatchedTasks += task
            enqueue(task)
        } else {
            result = ObjectEngineResult.of(source.schemaType, mutable = true)
            operation.passiveValues(queryOERDepth = 0).resolvePassiveObjectValues(
                source,
                OEROccurrence(result, emptyList(), result),
                Demand.checked(selections),
            )
        }
        drainTasks()
        check(children.isEmpty() && finished == dispatchedTasks) { "Reactor returned with unfinished tasks" }
        dispatchedTasks.filterIsInstance<DepthFirstOrchestrationTask>().forEach { task ->
            val target = task.objectOER.occurrence.target
            check(task.objectOER.closedValueSelections.groundKeys().all { target.isCellSet(it) && target.getCell(it).value.isCompleted }) {
                "Completed OER ${task.path} is missing closed demand"
            }
            val queryTarget = task.queryOER.occurrence.target
            check(
                task.queryOER.closedValueSelections.groundKeys().all { key ->
                    queryTarget.isCellSet(key) && queryTarget.getCell(key).value.isCompleted
                },
            ) {
                "Completed Query OER ${task.path} is missing closed demand"
            }
        }
        return result
    }

    private fun drainTasks() {
        while (tasks.isNotEmpty()) {
            val task = tasks.remove().task
            onTaskStarted(task)
            when (task) {
                is DepthFirstOrchestrationTask -> {
                    task.run()
                    check(orchestrated.add(task.objectOER.occurrence)) { "Object orchestrated twice: ${task.path}" }
                    children.remove(task.objectOER.occurrence)?.forEach(::enqueue)
                    check(orchestrated.add(task.queryOER.occurrence)) {
                        "Query orchestrated twice: ${task.path}"
                    }
                    children.remove(task.queryOER.occurrence)?.forEach(::enqueue)
                }
                is DepthFirstFieldResolverTask -> task.run()
                is DepthFirstMutationTask -> task.run()
            }
            check(finished.add(task)) { "Task finished twice: ${task.path}" }
        }
    }

    /**
     * Passive traversal discovers children before parents. Keep children off the runnable queue
     * until their parent has orchestrated, preserving the reactor's parent-before-child discovery.
     * Objects produced by a later field can enter the queue immediately because their parent ran.
     */
    override fun dispatchOrchestration(task: DepthFirstOrchestrationTask) {
        check(dispatchedTasks.add(task)) { "Orchestration task dispatched twice: ${task.path}" }
        val parent = task.objectOER.occurrence.parent
        if (parent == null || parent in orchestrated) {
            enqueue(task)
        } else {
            children.getOrPut(parent) { mutableListOf() } += task
        }
    }

    override fun dispatchFieldResolver(
        publication: GroundedFieldPublicationOccurrence<DepthFirstOperationContext>,
        queryOERDepth: Int,
    ) {
        // Preparation claims the cell, rejecting duplicate publication before queueing.
        val task = DepthFirstFieldResolverTask.prepare(publication, queryOERDepth)
        dispatchedTasks += task
        enqueue(task)
    }

    override suspend fun dispatchMutationField(
        publication: DepthFirstFieldResolverTask,
        cell: EngineResultCell,
    ) {
        check(dispatchedTasks.add(publication)) { "Mutation field task dispatched twice" }
        enqueue(publication)
        drainTasks()
    }

    override fun mutationNamespacePrepared(occurrence: OEROccurrence) {
        check(orchestrated.add(occurrence)) { "Mutation namespace orchestrated twice" }
        children.remove(occurrence)?.forEach(::enqueue)
    }

    private fun enqueue(task: DepthFirstTask) {
        tasks += ScheduledTask(task, nextSequence++)
    }
}

internal class ScheduledTask(
    val task: DepthFirstTask,
    val sequence: Long,
)

internal val depthFirstTaskComparator =
    compareByDescending<ScheduledTask> { it.task.queryOERDepth }
        .thenByDescending { it.task.path.size }
        .thenBy {
            when (it.task) {
                is DepthFirstFieldResolverTask -> 0
                is DepthFirstOrchestrationTask, is DepthFirstMutationTask -> 1
            }
        }
        .thenBy { it.sequence }
