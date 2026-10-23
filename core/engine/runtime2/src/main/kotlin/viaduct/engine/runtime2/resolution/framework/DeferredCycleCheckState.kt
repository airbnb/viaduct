package viaduct.engine.runtime2.resolution.framework

/**
 * Collects historical task dependencies without searching for cycles during reads or registration.
 *
 * Normal-path operations (`registerWriter`, `cycleCheck`) append to pre-sized logs under a
 * simple monitor. The dependency graph is only constructed inside [findCycle] on the timeout
 * path, avoiding all hash-map allocation during normal resolution.
 */
interface DeferredCycleCheckState : CycleCheckState {
    /** Returns a closed cycle path, or null, using one linear scan of the collected graph. */
    fun findCycle(): List<CycleTask>?
}

private class WriterEntry(val owner: Any, val kind: CycleSlotKind, val writer: CycleTask)

private class ReadEntry(val reader: CycleTask, val owner: Any, val kind: CycleSlotKind)

internal class DeferredCycleCheckStateImpl : DeferredCycleCheckState {
    private val writers = ArrayList<WriterEntry>(512)
    private val reads = ArrayList<ReadEntry>(2048)
    private val registeredWriters = Array(CycleSlotKind.entries.size) { java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()) }

    @Synchronized
    override fun registerWriter(
        owner: Any,
        kind: CycleSlotKind,
        writer: CycleTask,
    ) {
        check(registeredWriters[kind.ordinal].add(owner)) { "Writer already registered for slot: $writer" }
        writers.add(WriterEntry(owner, kind, writer))
    }

    @Synchronized
    override fun cycleCheck(
        reader: CycleTask,
        owner: Any,
        kind: CycleSlotKind,
    ) {
        reads.add(ReadEntry(reader, owner, kind))
    }

    @Synchronized
    override fun findCycle(): List<CycleTask>? {
        val slotWriters = Array(CycleSlotKind.entries.size) { IdentityHashMap<Any, CycleTask>() }
        for (entry in writers) {
            slotWriters[entry.kind.ordinal][entry.owner] = entry.writer
        }

        val dependencies = HashMap<CycleTask, MutableSet<CycleTask>>(reads.size)
        for (entry in reads) {
            val writer = slotWriters[entry.kind.ordinal][entry.owner] ?: continue
            dependencies.getOrPut(entry.reader) { mutableSetOf() }.add(writer)
        }

        val states = HashMap<CycleTask, VisitState>(dependencies.size)
        val pending = ArrayDeque<Traversal>()
        for (root in dependencies.keys) {
            if (root in states) continue
            states[root] = VisitState.ACTIVE
            pending.addLast(Traversal(root, dependencies[root].orEmpty().iterator()))
            while (pending.isNotEmpty()) {
                val current = pending.last()
                if (!current.successors.hasNext()) {
                    states[current.task] = VisitState.COMPLETE
                    pending.removeLast()
                    continue
                }
                val successor = current.successors.next()
                when (states[successor]) {
                    VisitState.ACTIVE -> return pending.map { it.task }.dropWhile { it != successor } + successor
                    VisitState.COMPLETE -> Unit
                    null -> {
                        states[successor] = VisitState.ACTIVE
                        pending.addLast(Traversal(successor, dependencies[successor].orEmpty().iterator()))
                    }
                }
            }
        }
        return null
    }

    private enum class VisitState { ACTIVE, COMPLETE }

    private class Traversal(
        val task: CycleTask,
        val successors: Iterator<CycleTask>,
    )
}

private class IdentityHashMap<K, V> : java.util.IdentityHashMap<K, V>()
