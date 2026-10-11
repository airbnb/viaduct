package viaduct.engine.runtime2.resolution.framework

/** Collects historical task dependencies without searching for cycles during reads or registration. */
interface DeferredCycleCheckState : CycleCheckState {
    /** Returns a closed cycle path, or null, using one linear scan of the collected graph. */
    fun findCycle(): List<CycleTask>?
}

internal class DeferredCycleCheckStateImpl : AbstractCycleCheckState(), DeferredCycleCheckState {
    @Synchronized
    override fun findCycle(): List<CycleTask>? {
        val states = mutableMapOf<CycleTask, VisitState>()
        val pending = ArrayDeque<Traversal>()
        for (root in readsByReader.keys) {
            if (root in states) continue
            states[root] = VisitState.ACTIVE
            pending.addLast(Traversal(root, readsByReader[root].orEmpty().iterator()))
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
                        pending.addLast(Traversal(successor, readsByReader[successor].orEmpty().iterator()))
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
