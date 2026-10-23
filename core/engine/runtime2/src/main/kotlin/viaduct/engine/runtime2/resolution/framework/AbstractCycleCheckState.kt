package viaduct.engine.runtime2.resolution.framework

import java.util.IdentityHashMap

/**
 * Shared construction of the historical task-dependency graph, independent of detection policy.
 *
 * Registration, reads, policy hooks, and subclass traversals use this instance's monitor. None
 * of these operations suspend or invoke resolver callbacks. Subclasses only read the adjacency
 * view while holding that monitor; only this class installs writers, pending readers, and edges.
 */
internal abstract class AbstractCycleCheckState : CycleCheckState {
    private val writersByKind = Array(CycleSlotKind.entries.size) { IdentityHashMap<Any, CycleTask>() }
    private val readersByKind = Array(CycleSlotKind.entries.size) { IdentityHashMap<Any, MutableSet<CycleTask>>() }
    private val dependencies = HashMap<CycleTask, MutableSet<CycleTask>>()

    protected val readsByReader: Map<CycleTask, Set<CycleTask>>
        get() = dependencies

    @Synchronized
    final override fun registerWriter(
        owner: Any,
        kind: CycleSlotKind,
        writer: CycleTask,
    ) {
        val writers = writersByKind[kind.ordinal]
        val previous = writers.putIfAbsent(owner, writer)
        check(previous == null) { "Writer already registered for slot: $previous" }
        readersByKind[kind.ordinal][owner]?.forEach { reader -> addRead(reader, writer) }
    }

    @Synchronized
    final override fun cycleCheck(
        reader: CycleTask,
        owner: Any,
        kind: CycleSlotKind,
    ) {
        val writer = writersByKind[kind.ordinal][owner]
        if (writer != null) {
            addRead(reader, writer)
            return
        }
        // Registration holds the same monitor, so it cannot race between lookup and insertion.
        readersByKind[kind.ordinal].getOrPut(owner) { mutableSetOf() }.add(reader)
    }

    private fun addRead(
        reader: CycleTask,
        writer: CycleTask,
    ) {
        val added = dependencies.getOrPut(reader) { mutableSetOf() }.add(writer)
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
