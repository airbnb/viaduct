package viaduct.engine.runtime2.model

/** A symbolic Boolean condition controlling whether one selection occurrence is included. */
sealed interface InclusionCondition {
    fun include(bindings: Map<Arguments.Variable, Boolean>): Boolean

    fun includeWith(binding: (Arguments.Variable) -> Boolean): Boolean

    suspend fun include(binding: suspend (Arguments.Variable) -> Boolean): Boolean

    fun and(other: InclusionCondition): InclusionCondition = conjunction(this, other)

    fun or(other: InclusionCondition): InclusionCondition = disjunction(this, other)

    fun usedVariables(): Set<Arguments.Variable>

    fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): InclusionCondition

    data object Always : InclusionCondition {
        override suspend fun include(binding: suspend (Arguments.Variable) -> Boolean) = true

        override fun include(bindings: Map<Arguments.Variable, Boolean>) = true

        override fun includeWith(binding: (Arguments.Variable) -> Boolean) = true

        override fun usedVariables(): Set<Arguments.Variable> = emptySet()

        override fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): InclusionCondition = this
    }

    data object Never : InclusionCondition {
        override suspend fun include(binding: suspend (Arguments.Variable) -> Boolean) = false

        override fun include(bindings: Map<Arguments.Variable, Boolean>) = false

        override fun includeWith(binding: (Arguments.Variable) -> Boolean) = false

        override fun usedVariables(): Set<Arguments.Variable> = emptySet()

        override fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): InclusionCondition = this
    }

    data class Requires(
        val values: Map<Arguments.Variable, Boolean>,
    ) : InclusionCondition {
        override suspend fun include(binding: suspend (Arguments.Variable) -> Boolean): Boolean {
            values.forEach { (variable, required) ->
                if (binding(variable) != required) return false
            }
            return true
        }

        override fun include(bindings: Map<Arguments.Variable, Boolean>): Boolean = values.all { (variable, required) -> bindings.getValue(variable) == required }

        override fun includeWith(binding: (Arguments.Variable) -> Boolean): Boolean = values.all { (variable, required) -> binding(variable) == required }

        override fun usedVariables(): Set<Arguments.Variable> = values.keys

        override fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): InclusionCondition =
            values.entries.fold(Always as InclusionCondition) { condition, (variable, required) ->
                condition.and(requires(mapOf(transform(variable) to required)))
            }
    }

    companion object {
        fun requires(values: Map<Arguments.Variable, Boolean>): InclusionCondition = if (values.isEmpty()) Always else Requires(values.toMap())

        fun anyOf(conditions: Iterable<InclusionCondition>): InclusionCondition = conditions.fold(Never as InclusionCondition, InclusionCondition::or)
    }
}

/** A conjunction node deliberately retains its operands instead of distributing them. */
internal class Conjunction(val left: InclusionCondition, val right: InclusionCondition) : InclusionCondition {
    private val hashCode = 31 * left.hashCode() + right.hashCode()

    override suspend fun include(binding: suspend (Arguments.Variable) -> Boolean) = evaluateConditionSuspending(this) { it.include(binding) }

    override fun include(bindings: Map<Arguments.Variable, Boolean>) = evaluateCondition(this) { it.include(bindings) }

    override fun includeWith(binding: (Arguments.Variable) -> Boolean) = evaluateCondition(this) { it.includeWith(binding) }

    override fun usedVariables(): Set<Arguments.Variable> = collectUsedVariables(this)

    override fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable) = mapConditionVariables(this, transform)

    override fun equals(other: Any?): Boolean = other is Conjunction && conditionsEqual(this, other)

    override fun hashCode(): Int = hashCode
}

/** A disjunction node retains sharing between compact condition subgraphs. */
internal class Disjunction(val left: InclusionCondition, val right: InclusionCondition) : InclusionCondition {
    private val hashCode = 31 * left.hashCode() + right.hashCode()
    val hasOnlyRequirementAlternatives: Boolean =
        (left is InclusionCondition.Requires || left is Disjunction && left.hasOnlyRequirementAlternatives) &&
            (right is InclusionCondition.Requires || right is Disjunction && right.hasOnlyRequirementAlternatives)

    override suspend fun include(binding: suspend (Arguments.Variable) -> Boolean) = evaluateConditionSuspending(this) { it.include(binding) }

    override fun include(bindings: Map<Arguments.Variable, Boolean>) = evaluateCondition(this) { it.include(bindings) }

    override fun includeWith(binding: (Arguments.Variable) -> Boolean) = evaluateCondition(this) { it.includeWith(binding) }

    override fun usedVariables(): Set<Arguments.Variable> = collectUsedVariables(this)

    override fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable) = mapConditionVariables(this, transform)

    override fun equals(other: Any?): Boolean = other is Disjunction && conditionsEqual(this, other)

    override fun hashCode(): Int = hashCode
}

private fun conjunction(
    first: InclusionCondition,
    second: InclusionCondition,
): InclusionCondition =
    when {
        first === InclusionCondition.Never || second === InclusionCondition.Never ->
            InclusionCondition.Never
        first === InclusionCondition.Always -> second
        second === InclusionCondition.Always -> first
        first === second -> first
        first is InclusionCondition.Requires && second is InclusionCondition.Requires -> {
            val conflicts =
                first.values.any { (variable, value) ->
                    second.values[variable]?.let { it != value } == true
                }
            if (conflicts) {
                InclusionCondition.Never
            } else {
                InclusionCondition.requires(first.values + second.values)
            }
        }
        first is Disjunction && second is InclusionCondition.Requires ->
            conjunctionIfPruned(first, second) ?: Conjunction(first, second)
        first is InclusionCondition.Requires && second is Disjunction ->
            conjunctionIfPruned(second, first, requirementsFirst = true) ?: Conjunction(first, second)
        else -> Conjunction(first, second)
    }

/** Drops directly contradicted alternatives without traversing or distributing viable branches. */
private fun conjunctionIfPruned(
    alternatives: Disjunction,
    requirements: InclusionCondition.Requires,
    requirementsFirst: Boolean = false,
): InclusionCondition? {
    val leftConflicts = alternatives.left.conflictsDirectlyWith(requirements)
    val rightConflicts = alternatives.right.conflictsDirectlyWith(requirements)
    val remaining = when {
        leftConflicts && rightConflicts -> InclusionCondition.Never
        leftConflicts -> alternatives.right
        rightConflicts -> alternatives.left
        else -> return null
    }
    if (remaining is Disjunction) {
        return if (requirementsFirst) Conjunction(requirements, remaining) else Conjunction(remaining, requirements)
    }
    return if (requirementsFirst) conjunction(requirements, remaining) else conjunction(remaining, requirements)
}

private fun InclusionCondition.conflictsDirectlyWith(requirements: InclusionCondition.Requires): Boolean =
    this is InclusionCondition.Requires &&
        values.any { (variable, value) -> requirements.values[variable]?.let { it != value } == true }

private fun disjunction(
    first: InclusionCondition,
    second: InclusionCondition,
): InclusionCondition =
    when {
        first === InclusionCondition.Always || second === InclusionCondition.Always -> InclusionCondition.Always
        first === InclusionCondition.Never -> second
        second === InclusionCondition.Never -> first
        first === second || first == second -> first
        else -> Disjunction(first, second)
    }

/**
 * Prunes contradicted branches and duplicate guarded requirement alternatives before binding.
 * Duplicate maps retain the original first alternative and its binding order. Common guards
 * are extracted conservatively; no conjunction is distributed over an OR, and this is not
 * a satisfiability decision or complete DNF normalization for arbitrary formulas.
 */
internal fun InclusionCondition.pruneContradictions(): InclusionCondition {
    val polarities = mutableMapOf<Arguments.Variable, Int>()
    val occurrences = mutableMapOf<Arguments.Variable, Int>()
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InclusionCondition, Boolean>())
    val pending = ArrayDeque<InclusionCondition>()
    pending += this
    while (pending.isNotEmpty()) {
        val condition = pending.removeLast()
        if (!visited.add(condition)) continue
        when (condition) {
            InclusionCondition.Always, InclusionCondition.Never -> Unit
            is InclusionCondition.Requires -> condition.values.forEach { (variable, value) ->
                polarities[variable] = (polarities[variable] ?: 0) or if (value) 1 else 2
                occurrences[variable] = (occurrences[variable] ?: 0) + 1
            }
            is Conjunction -> {
                pending += condition.right
                pending += condition.left
            }
            is Disjunction -> {
                pending += condition.right
                pending += condition.left
            }
        }
    }
    if (occurrences.values.none { it > 1 }) return this

    val pruned = mutableMapOf<Map<Arguments.Variable, Boolean>, java.util.IdentityHashMap<InclusionCondition, InclusionCondition>>()
    val normalized = mutableMapOf<Map<Arguments.Variable, Boolean>, java.util.IdentityHashMap<InclusionCondition, InclusionCondition>>()
    val guaranteed = java.util.IdentityHashMap<InclusionCondition, InclusionCondition.Requires?>()

    // A conjunct guarantees all its requirements; a flat OR guarantees only their intersection.
    // Other OR shapes contribute no guard. Do not distribute them to infer stronger guarantees.
    fun guaranteedGuard(root: InclusionCondition): InclusionCondition.Requires? {
        if (root is InclusionCondition.Requires) return root
        if (guaranteed.containsKey(root)) return guaranteed[root]
        val values = mutableMapOf<Arguments.Variable, Boolean>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InclusionCondition, Boolean>())
        val pendingGuards = ArrayDeque<InclusionCondition>()
        pendingGuards += root
        while (pendingGuards.isNotEmpty()) {
            val condition = pendingGuards.removeLast()
            if (!seen.add(condition)) continue
            val required = when (condition) {
                is InclusionCondition.Requires -> condition.values
                is Conjunction -> {
                    pendingGuards += condition.right
                    pendingGuards += condition.left
                    continue
                }
                is Disjunction -> {
                    if (!condition.hasOnlyRequirementAlternatives) continue
                    val common = mutableMapOf<Arguments.Variable, Boolean>()
                    val alternatives = ArrayDeque<InclusionCondition>()
                    val visitedAlternatives = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InclusionCondition, Boolean>())
                    alternatives += condition
                    var first = true
                    while (alternatives.isNotEmpty()) {
                        val alternative = alternatives.removeLast()
                        if (!visitedAlternatives.add(alternative)) continue
                        if (alternative is Disjunction) {
                            alternatives += alternative.right
                            alternatives += alternative.left
                        } else if (alternative is InclusionCondition.Requires) {
                            if (first) common.putAll(alternative.values) else common.entries.removeIf { alternative.values[it.key] != it.value }
                            first = false
                            if (common.isEmpty()) break
                        }
                    }
                    common
                }
                else -> continue
            }
            if (required.any { (variable, value) -> values[variable]?.let { it != value } == true }) {
                guaranteed[root] = null
                return null
            }
            values.putAll(required)
        }
        val guard = InclusionCondition.requires(values) as? InclusionCondition.Requires
        guaranteed[root] = guard
        return guard
    }

    fun normalizeAlternatives(
        root: InclusionCondition,
        guard: InclusionCondition.Requires
    ): InclusionCondition {
        val requirements = guard.values.filterKeys { occurrences.getValue(it) > 1 }
        if (requirements.isEmpty()) return root
        val cache = normalized.getOrPut(requirements) { java.util.IdentityHashMap() }
        val frames = ArrayDeque<Pair<InclusionCondition, Boolean>>()
        frames += root to false
        while (frames.isNotEmpty()) {
            val (condition, expanded) = frames.removeLast()
            if (cache.containsKey(condition)) continue
            if (condition is Disjunction && condition.hasOnlyRequirementAlternatives) {
                val distinct = linkedMapOf<Map<Arguments.Variable, Boolean>, InclusionCondition>()
                val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InclusionCondition, Boolean>())
                val pendingAlternatives = ArrayDeque<InclusionCondition>()
                pendingAlternatives += condition
                var changed = false
                while (pendingAlternatives.isNotEmpty()) {
                    val alternative = pendingAlternatives.removeLast()
                    if (!seen.add(alternative)) continue
                    if (alternative is Disjunction) {
                        pendingAlternatives += alternative.right
                        pendingAlternatives += alternative.left
                    } else if (alternative is InclusionCondition.Requires) {
                        if (alternative.values.any { (variable, value) -> requirements[variable]?.let { it != value } == true }) {
                            changed = true
                        } else {
                            // These keys are equal exactly when the guarded requirement maps would
                            // be equal. Keep the first leaf rather than moving the guard's reads.
                            val remaining = alternative.values.filterKeys { it !in requirements }
                            if (distinct.putIfAbsent(remaining, alternative) != null) changed = true
                        }
                    }
                }
                cache[condition] = if (changed) InclusionCondition.anyOf(distinct.values) else condition
                continue
            }
            if (!expanded) {
                when (condition) {
                    is Conjunction -> {
                        frames += condition to true
                        frames += condition.right to false
                        frames += condition.left to false
                        continue
                    }
                    is Disjunction -> {
                        frames += condition to true
                        frames += condition.right to false
                        frames += condition.left to false
                        continue
                    }
                    else -> Unit
                }
            }
            cache[condition] = when (condition) {
                is Conjunction -> {
                    val left = cache.getValue(condition.left)
                    val right = cache.getValue(condition.right)
                    if (left === condition.left && right === condition.right) condition else conjunction(left, right)
                }
                is Disjunction -> {
                    val left = cache.getValue(condition.left)
                    val right = cache.getValue(condition.right)
                    if (left === condition.left && right === condition.right) condition else disjunction(left, right)
                }
                else -> condition
            }
        }
        return cache.getValue(root)
    }

    fun rewrite(
        root: InclusionCondition,
        guard: InclusionCondition.Requires?
    ): InclusionCondition {
        // Requirements with only one polarity cannot prune anything. Ignoring them lets guards
        // with the same possible conflicts reuse prefix rewrites instead of rescanning the graph.
        val rewritten = if (guard == null) {
            java.util.IdentityHashMap()
        } else {
            val conflicts = guard.values.filterKeys { polarities[it] == 3 }
            pruned.getOrPut(conflicts) { java.util.IdentityHashMap() }
        }
        val frames = ArrayDeque<Pair<InclusionCondition, Boolean>>()
        frames += root to false
        while (frames.isNotEmpty()) {
            val (condition, expanded) = frames.removeLast()
            if (rewritten.containsKey(condition)) continue
            if (!expanded) {
                when (condition) {
                    is Conjunction -> {
                        frames += condition to true
                        frames += condition.right to false
                        frames += condition.left to false
                        continue
                    }
                    is Disjunction -> {
                        frames += condition to true
                        frames += condition.right to false
                        frames += condition.left to false
                        continue
                    }
                    else -> Unit
                }
            }
            rewritten[condition] = when (condition) {
                InclusionCondition.Always, InclusionCondition.Never -> condition
                is InclusionCondition.Requires ->
                    if (guard != null && condition.conflictsDirectlyWith(guard)) InclusionCondition.Never else condition
                is Conjunction -> {
                    var left = rewritten.getValue(condition.left)
                    var right = rewritten.getValue(condition.right)
                    if (guard == null) {
                        if (left is InclusionCondition.Requires && left.values.keys.any { polarities[it] == 3 }) right = rewrite(right, left)
                        if (right is InclusionCondition.Requires && right.values.keys.any { polarities[it] == 3 }) left = rewrite(left, right)
                        if (left is Conjunction || left is Disjunction) guaranteedGuard(right)?.let { left = normalizeAlternatives(left, it) }
                        if (right is Conjunction || right is Disjunction) guaranteedGuard(left)?.let { right = normalizeAlternatives(right, it) }
                    }
                    if (left === condition.left && right === condition.right) condition else conjunction(left, right)
                }
                is Disjunction -> {
                    val left = rewritten.getValue(condition.left)
                    val right = rewritten.getValue(condition.right)
                    if (left === condition.left && right === condition.right) condition else disjunction(left, right)
                }
            }
        }
        return rewritten.getValue(root)
    }
    return rewrite(this, null)
}

private fun collectUsedVariables(root: InclusionCondition): Set<Arguments.Variable> {
    val variables = linkedSetOf<Arguments.Variable>()
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InclusionCondition, Boolean>())
    val pending = ArrayDeque<InclusionCondition>()
    pending += root
    while (pending.isNotEmpty()) {
        when (val condition = pending.removeLast()) {
            InclusionCondition.Always, InclusionCondition.Never -> Unit
            is InclusionCondition.Requires -> variables += condition.values.keys
            is Conjunction -> {
                if (!visited.add(condition)) continue
                pending += condition.right
                pending += condition.left
            }
            is Disjunction -> {
                if (!visited.add(condition)) continue
                pending += condition.right
                pending += condition.left
            }
        }
    }
    return variables
}

/** Evaluates a condition after excluding every requirement with a missing binding. */
internal fun InclusionCondition.includeIfBound(bindings: Map<Arguments.Variable, Boolean>): Boolean =
    evaluateCondition(this) { requirement ->
        requirement.values.all { (variable, required) -> bindings[variable]?.let { it == required } == true }
    }

private class EvaluationFrame(
    val condition: InclusionCondition,
    val exhaustive: Boolean = false,
    var state: Int = 0,
    var operandResult: Boolean = false,
)

/**
 * An ordinary OR stops at its first success. If a later conjunct rejects that success,
 * the left operand must also inspect its other alternatives: their failures still matter.
 * Both traversal modes memoize shared nodes without enumerating Cartesian products.
 */
private class ConditionEvaluation(root: InclusionCondition) {
    private val evaluated = java.util.IdentityHashMap<InclusionCondition, Boolean>()
    private val exhaustivelyEvaluated = java.util.IdentityHashMap<InclusionCondition, Boolean>()
    private val pending = ArrayDeque<EvaluationFrame>().apply { addLast(EvaluationFrame(root)) }
    var result = false
        private set

    fun nextRequirement(): InclusionCondition.Requires? {
        while (pending.isNotEmpty()) {
            val frame = pending.last()
            val results = if (frame.exhaustive) exhaustivelyEvaluated else evaluated
            val cached = results[frame.condition]
            if (cached != null) {
                result = cached
                pending.removeLast()
                continue
            }
            var completed = true
            when (val condition = frame.condition) {
                InclusionCondition.Always -> {
                    result = true
                    pending.removeLast()
                }
                InclusionCondition.Never -> {
                    result = false
                    pending.removeLast()
                }
                is InclusionCondition.Requires -> return condition
                is Conjunction ->
                    when (frame.state) {
                        0 -> {
                            frame.state = 1
                            pending += EvaluationFrame(condition.left)
                            completed = false
                        }
                        1 ->
                            if (result) {
                                frame.state = 2
                                pending += EvaluationFrame(condition.right, frame.exhaustive)
                                completed = false
                            } else {
                                pending.removeLast()
                            }
                        2 ->
                            if (result && !frame.exhaustive) {
                                pending.removeLast()
                            } else {
                                frame.operandResult = result
                                frame.state = 3
                                pending += EvaluationFrame(condition.left, exhaustive = true)
                                completed = false
                            }
                        else -> {
                            result = result && frame.operandResult
                            pending.removeLast()
                        }
                    }
                is Disjunction ->
                    when (frame.state) {
                        0 -> {
                            frame.state = 1
                            pending += EvaluationFrame(condition.left, frame.exhaustive)
                            completed = false
                        }
                        1 ->
                            if (result && !frame.exhaustive) {
                                pending.removeLast()
                            } else {
                                frame.operandResult = result
                                frame.state = 2
                                pending += EvaluationFrame(condition.right, frame.exhaustive)
                                completed = false
                            }
                        else -> {
                            result = frame.operandResult || result
                            pending.removeLast()
                        }
                    }
            }
            if (completed) results[frame.condition] = result
        }
        return null
    }

    fun completeRequirement(included: Boolean) {
        val condition = pending.removeLast().condition
        result = included
        evaluated[condition] = included
        exhaustivelyEvaluated[condition] = included
    }
}

private fun evaluateCondition(
    root: InclusionCondition,
    requirement: (InclusionCondition.Requires) -> Boolean,
): Boolean {
    val evaluation = ConditionEvaluation(root.pruneContradictions())
    while (true) {
        val pending = evaluation.nextRequirement() ?: return evaluation.result
        evaluation.completeRequirement(requirement(pending))
    }
}

private suspend fun evaluateConditionSuspending(
    root: InclusionCondition,
    requirement: suspend (InclusionCondition.Requires) -> Boolean,
): Boolean {
    val evaluation = ConditionEvaluation(root.pruneContradictions())
    while (true) {
        val pending = evaluation.nextRequirement() ?: return evaluation.result
        evaluation.completeRequirement(requirement(pending))
    }
}

private fun mapConditionVariables(
    root: InclusionCondition,
    transform: (Arguments.Variable) -> Arguments.Variable,
): InclusionCondition {
    val mapped = java.util.IdentityHashMap<InclusionCondition, InclusionCondition>()
    val pending = ArrayDeque<Pair<InclusionCondition, Boolean>>()
    pending += root to false
    while (pending.isNotEmpty()) {
        val (condition, expanded) = pending.removeLast()
        if (mapped.containsKey(condition)) continue
        when {
            condition is Conjunction && !expanded -> {
                pending += condition to true
                pending += condition.right to false
                pending += condition.left to false
            }
            condition is Disjunction && !expanded -> {
                pending += condition to true
                pending += condition.right to false
                pending += condition.left to false
            }
            else ->
                mapped[condition] =
                    when (condition) {
                        InclusionCondition.Always, InclusionCondition.Never -> condition
                        is InclusionCondition.Requires -> condition.mapVariables(transform)
                        is Conjunction -> conjunction(mapped.getValue(condition.left), mapped.getValue(condition.right))
                        is Disjunction -> disjunction(mapped.getValue(condition.left), mapped.getValue(condition.right))
                    }
        }
    }
    return mapped.getValue(root)
}

private fun conditionsEqual(
    first: InclusionCondition,
    second: InclusionCondition,
): Boolean {
    val compared = java.util.IdentityHashMap<InclusionCondition, MutableSet<InclusionCondition>>()
    val pending = ArrayDeque<Pair<InclusionCondition, InclusionCondition>>()
    pending += first to second
    while (pending.isNotEmpty()) {
        val (left, right) = pending.removeLast()
        if (left === right) continue
        val rights = compared.getOrPut(left) { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<InclusionCondition, Boolean>()) }
        if (!rights.add(right)) continue
        when {
            left is InclusionCondition.Requires && right is InclusionCondition.Requires -> if (left.values != right.values) return false
            left is Conjunction && right is Conjunction -> {
                pending += left.right to right.right
                pending += left.left to right.left
            }
            left is Disjunction && right is Disjunction -> {
                pending += left.right to right.right
                pending += left.left to right.left
            }
            else -> return false
        }
    }
    return true
}
