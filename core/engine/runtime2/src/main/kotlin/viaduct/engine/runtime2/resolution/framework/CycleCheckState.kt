package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent

/** The kind of task that owns one cycle-graph node. */
enum class CycleTaskKind {
    FIELD_RESOLVER,
    FIELD_CHECKER,
    TYPE_CHECKER,
}

/**
 * Exact identity of one task in one result occurrence.
 *
 * Query roots and result cells are occurrence identities, so equality deliberately uses reference
 * equality for [root] rather than the value-like equality of the root's completed contents.
 */
class CycleTask(
    val kind: CycleTaskKind,
    val root: ObjectEngineResult,
    path: List<PathComponent>,
) {
    val path: List<PathComponent> = path.toList()
    private val cachedHashCode = 31 * (31 * kind.hashCode() + System.identityHashCode(root)) + this.path.hashCode()

    override fun equals(other: Any?): Boolean =
        other is CycleTask &&
            kind == other.kind &&
            root === other.root &&
            path == other.path

    override fun hashCode(): Int = cachedHashCode

    override fun toString(): String = "$kind@${System.identityHashCode(root)}:$path"

    companion object {
        fun fieldResolver(
            root: ObjectEngineResult,
            path: List<PathComponent>,
        ): CycleTask = CycleTask(CycleTaskKind.FIELD_RESOLVER, root, path)

        fun fieldChecker(
            root: ObjectEngineResult,
            path: List<PathComponent>,
        ): CycleTask = CycleTask(CycleTaskKind.FIELD_CHECKER, root, path)

        fun typeChecker(
            root: ObjectEngineResult,
            path: List<PathComponent>,
        ): CycleTask = CycleTask(CycleTaskKind.TYPE_CHECKER, root, path)
    }
}

internal fun ObjectEngineResult.fieldResolverCycleTask(path: List<PathComponent>): CycleTask = CycleTask.fieldResolver(this, path)

internal fun ObjectEngineResult.fieldCheckerCycleTask(path: List<PathComponent>): CycleTask = CycleTask.fieldChecker(this, path)

internal fun ObjectEngineResult.typeCheckerCycleTask(path: List<PathComponent>): CycleTask = CycleTask.typeChecker(this, path)

internal fun OEROccurrence.fieldResolverCycleTask(key: ObjectEngineResult.ObjectKey): CycleTask = root.fieldResolverCycleTask(coordinate(key))

internal fun OEROccurrence.fieldCheckerCycleTask(key: ObjectEngineResult.ObjectKey): CycleTask = root.fieldCheckerCycleTask(coordinate(key))

internal fun OEROccurrence.typeCheckerCycleTask(): CycleTask = root.typeCheckerCycleTask(path)

/** The independent result slot read or written by a task. */
enum class CycleSlotKind {
    VALUE,
    FIELD_CHECKER,
    TYPE_CHECKER,
}

/** Exact identity of one independently readable result slot. */
class CycleSlot private constructor(
    val kind: CycleSlotKind,
    private val owner: Any,
) {
    internal val cellOwner: EngineResultCell?
        get() = owner as? EngineResultCell

    constructor(
        kind: CycleSlotKind,
        cell: EngineResultCell,
    ) : this(kind, cell as Any) {
        require(kind != CycleSlotKind.TYPE_CHECKER) {
            "A type-checker cycle slot belongs to an ObjectEngineResult"
        }
    }

    override fun equals(other: Any?): Boolean = other is CycleSlot && kind == other.kind && owner === other.owner

    override fun hashCode(): Int = 31 * kind.hashCode() + System.identityHashCode(owner)

    override fun toString(): String = "$kind@${System.identityHashCode(owner)}"

    companion object {
        fun value(cell: EngineResultCell): CycleSlot = CycleSlot(CycleSlotKind.VALUE, cell)

        fun fieldChecker(cell: EngineResultCell): CycleSlot = CycleSlot(CycleSlotKind.FIELD_CHECKER, cell)

        fun typeChecker(result: ObjectEngineResult): CycleSlot = CycleSlot(CycleSlotKind.TYPE_CHECKER, result)
    }
}

internal val EngineResultCell.valueCycleSlot: CycleSlot
    get() = CycleSlot.value(this)

internal val EngineResultCell.fieldCheckerCycleSlot: CycleSlot
    get() = CycleSlot.fieldChecker(this)

internal val ObjectEngineResult.typeCheckerCycleSlot: CycleSlot
    get() = CycleSlot.typeChecker(this)

/** Tracks exact task-to-slot reads with eager rejection or explicit deferred cycle diagnosis. */
interface CycleCheckState {
    fun registerWriter(
        slot: CycleSlot,
        writer: CycleTask,
    )

    fun cycleCheck(
        reader: CycleTask,
        slot: CycleSlot,
    )

    companion object {
        fun create(): CycleCheckState = CycleCheckStateImpl()

        fun createDeferred(): DeferredCycleCheckState = DeferredCycleCheckStateImpl()

        fun createNOP(): CycleCheckState = NOPCycleCheckState
    }
}

internal class ResolverReadCycleException(
    val cycle: List<CycleTask>,
) : IllegalStateException(
        "Resolver-read cycle: ${cycle.joinToString(separator = " -> ")}",
    )

private object NOPCycleCheckState : CycleCheckState {
    override fun registerWriter(
        slot: CycleSlot,
        writer: CycleTask,
    ) {}

    override fun cycleCheck(
        reader: CycleTask,
        slot: CycleSlot,
    ) {}
}

private class CycleCheckStateImpl : AbstractCycleCheckState() {
    private var hasRejectedCycle = false

    override fun onDependencyRecorded(
        reader: CycleTask,
        writer: CycleTask,
        added: Boolean,
    ) {
        // While the graph is acyclic, an existing edge cannot introduce a cycle. Rejected
        // edges remain in the graph, so subsequent reads must keep checking for cycles.
        if (!added && !hasRejectedCycle) return

        pathFrom(writer, reader)?.let { writerToReader ->
            hasRejectedCycle = true
            throw ResolverReadCycleException(listOf(reader) + writerToReader)
        }
    }

    private fun pathFrom(
        start: CycleTask,
        destination: CycleTask,
    ): List<CycleTask>? {
        if (start == destination) return listOf(start)

        val pending = ArrayDeque<CycleTask>()
        val visited = mutableSetOf(start)
        val predecessor = mutableMapOf<CycleTask, CycleTask>()
        pending += start
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            readsByReader[current].orEmpty().forEach { successor ->
                if (!visited.add(successor)) return@forEach
                predecessor[successor] = current
                if (successor == destination) {
                    return reconstructPath(start, destination, predecessor)
                }
                pending += successor
            }
        }
        return null
    }
}

private fun reconstructPath(
    start: CycleTask,
    destination: CycleTask,
    predecessor: Map<CycleTask, CycleTask>,
): List<CycleTask> {
    val reversed = mutableListOf(destination)
    var current = destination
    while (current != start) {
        current = checkNotNull(predecessor[current])
        reversed += current
    }
    return reversed.asReversed()
}
