package viaduct.engine.runtime2.model

internal fun InclusionCondition.nodeCount(): Int {
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InclusionCondition, Boolean>())
    val pending = ArrayDeque<InclusionCondition>()
    pending += this
    while (pending.isNotEmpty()) {
        when (val condition = pending.removeLast()) {
            is Conjunction -> if (visited.add(condition)) {
                pending += condition.right
                pending += condition.left
            }
            is Disjunction -> if (visited.add(condition)) {
                pending += condition.right
                pending += condition.left
            }
            else -> visited += condition
        }
    }
    return visited.size
}
