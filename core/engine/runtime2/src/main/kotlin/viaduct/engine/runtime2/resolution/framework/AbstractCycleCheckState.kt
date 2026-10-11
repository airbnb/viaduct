package viaduct.engine.runtime2.resolution.framework

import java.util.concurrent.ConcurrentHashMap

/**
 * Shared construction of the historical task-dependency graph, independent of detection policy.
 *
 * Registration, reads, policy hooks, and subclass traversals use this instance's monitor. None
 * of these operations suspend or invoke resolver callbacks. Subclasses only read the adjacency
 * view while holding that monitor; only this class installs writers, pending readers, and edges.
 */
internal abstract class AbstractCycleCheckState : CycleCheckState {
    private val writersBySlot = ConcurrentHashMap<CycleSlot, CycleTask>()
    private val readersBySlot = ConcurrentHashMap<CycleSlot, MutableSet<CycleTask>>()
    private val dependencies = ConcurrentHashMap<CycleTask, MutableSet<CycleTask>>()

    protected val readsByReader: Map<CycleTask, Set<CycleTask>>
        get() = dependencies

    @Synchronized
    final override fun registerWriter(
        slot: CycleSlot,
        writer: CycleTask,
    ) {
        val previous = writersBySlot.putIfAbsent(slot, writer)
        check(previous == null) { "Writer already registered for slot: $previous" }
        readersBySlot[slot].orEmpty().forEach { reader -> addRead(reader, writer) }
    }

    @Synchronized
    final override fun cycleCheck(
        reader: CycleTask,
        slot: CycleSlot,
    ) {
        val writer = writersBySlot[slot]
        if (writer != null) {
            addRead(reader, writer)
            return
        }
        // Registration holds the same monitor, so it cannot race between lookup and insertion.
        readersBySlot.computeIfAbsent(slot) { ConcurrentHashMap.newKeySet() }.add(reader)
    }

    private fun addRead(
        reader: CycleTask,
        writer: CycleTask,
    ) {
        val added = dependencies.computeIfAbsent(reader) { ConcurrentHashMap.newKeySet() }.add(writer)
        // Record before invoking policy: a rejected edge remains historical evidence, and a
        // rejection during late-writer hookup stops processing the remaining pending readers.
        onDependencyRecorded(reader, writer, added)
    }

    /** Runs under the graph monitor after insertion, including attempts to add duplicate edges. */
    protected open fun onDependencyRecorded(
        reader: CycleTask,
        writer: CycleTask,
        added: Boolean,
    ) {}
}
