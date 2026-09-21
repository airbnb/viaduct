package semantics.shared

import model.EngineResultCell
import model.ObjectEngineResult
import model.PathComponent
import java.util.concurrent.ConcurrentHashMap

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

    override fun equals(other: Any?): Boolean =
        other is CycleTask &&
            kind == other.kind &&
            root === other.root &&
            path == other.path

    override fun hashCode(): Int =
        31 * (31 * kind.hashCode() + System.identityHashCode(root)) + path.hashCode()

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
    }
}

internal fun ObjectEngineResult.fieldResolverCycleTask(
    path: List<PathComponent>,
): CycleTask = CycleTask.fieldResolver(this, path)

internal fun ObjectEngineResult.fieldCheckerCycleTask(
    path: List<PathComponent>,
): CycleTask = CycleTask.fieldChecker(this, path)

internal fun OEROccurrence.fieldResolverCycleTask(
    key: ObjectEngineResult.ObjectKey,
): CycleTask = root.fieldResolverCycleTask(coordinate(key))

internal fun OEROccurrence.fieldCheckerCycleTask(
    key: ObjectEngineResult.ObjectKey,
): CycleTask = root.fieldCheckerCycleTask(coordinate(key))

/** The independent result slot read or written by a task. */
enum class CycleSlotKind {
    VALUE,
    FIELD_CHECKER,
    TYPE_CHECKER,
}

/** Exact identity of one slot on one result cell. */
class CycleSlot(
    val kind: CycleSlotKind,
    val cell: EngineResultCell,
) {
    override fun equals(other: Any?): Boolean =
        other is CycleSlot && kind == other.kind && cell === other.cell

    override fun hashCode(): Int = 31 * kind.hashCode() + System.identityHashCode(cell)

    override fun toString(): String = "$kind@${System.identityHashCode(cell)}"

    companion object {
        fun value(cell: EngineResultCell): CycleSlot =
            CycleSlot(CycleSlotKind.VALUE, cell)

        fun fieldChecker(cell: EngineResultCell): CycleSlot =
            CycleSlot(CycleSlotKind.FIELD_CHECKER, cell)
    }
}

internal val EngineResultCell.valueCycleSlot: CycleSlot
    get() = CycleSlot.value(this)

internal val EngineResultCell.fieldCheckerCycleSlot: CycleSlot
    get() = CycleSlot.fieldChecker(this)

/** Tracks exact task-to-slot reads and rejects cycles in the resulting writer dependency graph. */
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

private class CycleCheckStateImpl : CycleCheckState {
    private val writersBySlot =
        ConcurrentHashMap<CycleSlot, CycleTask>()
    private val readersBySlot =
        ConcurrentHashMap<CycleSlot, MutableSet<CycleTask>>()
    private val readsByReader =
        ConcurrentHashMap<CycleTask, MutableSet<CycleTask>>()

    override fun registerWriter(
        slot: CycleSlot,
        writer: CycleTask,
    ) {
        val previous = writersBySlot.putIfAbsent(slot, writer)
        check(previous == null) {
            "Writer already registered for slot: $previous"
        }
        readersBySlot[slot].orEmpty().forEach { reader ->
            addRead(reader, writer)
        }
    }

    override fun cycleCheck(
        reader: CycleTask,
        slot: CycleSlot,
    ) {
        readersBySlot
            .computeIfAbsent(slot) {
                ConcurrentHashMap.newKeySet()
            }.add(reader)
        writersBySlot[slot]?.let { writer ->
            addRead(reader, writer)
        }
    }

    @Synchronized
    private fun addRead(
        reader: CycleTask,
        writer: CycleTask,
    ) {
        readsByReader
            .computeIfAbsent(reader) {
                // One coroutine normally owns a reader's set; keep it concurrent defensively.
                ConcurrentHashMap.newKeySet()
            }.add(writer)

        pathFrom(writer, reader)?.let { writerToReader ->
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
