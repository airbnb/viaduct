package viaduct.engine.runtime2.resolution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Conjunction
import viaduct.engine.runtime2.model.Disjunction
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.pruneContradictions

private data class EvaluationSummary(
    val hasTrue: Boolean = false,
    val hasFalse: Boolean = false,
    val error: Throwable? = null,
    val hasPending: Boolean = false,
)

private class EvaluationNode(
    val condition: InclusionCondition,
) {
    var left: EvaluationNode? = null
    var right: EvaluationNode? = null
    lateinit var summary: EvaluationSummary
}

private class EvaluationCompletion(
    val node: EvaluationNode,
    val result: Result<Boolean>,
    val fatal: Throwable? = null,
)

/** Evaluates implicit DNF outcomes with incremental propagation over the compact graph. */
@Suppress("TooGenericExceptionCaught") // Fatal children must signal the receiver before rethrowing.
internal suspend fun InclusionCondition.includeAnyReadyAlternative(binding: suspend (Arguments.Variable) -> Boolean): Boolean =
    supervisorScope {
        val prunedCondition = this@includeAnyReadyAlternative.pruneContradictions()
        if (prunedCondition === InclusionCondition.Always) return@supervisorScope true
        if (prunedCondition === InclusionCondition.Never) return@supervisorScope false
        val nodes = java.util.IdentityHashMap<InclusionCondition, EvaluationNode>()
        val parents = java.util.IdentityHashMap<EvaluationNode, MutableList<EvaluationNode>>()
        val evaluations = linkedMapOf<EvaluationNode, Deferred<Unit>>()
        // Each leaf contributes at most one signal. Waiting never re-registers the other leaves.
        val completions = Channel<EvaluationCompletion>(Channel.UNLIMITED)

        fun disjunction(
            left: EvaluationSummary,
            right: EvaluationSummary,
        ) = EvaluationSummary(
            hasTrue = left.hasTrue || right.hasTrue,
            hasFalse = left.hasFalse || right.hasFalse,
            error = left.error ?: right.error,
            hasPending = left.hasPending || right.hasPending,
        )

        fun conjunction(
            left: EvaluationSummary,
            right: EvaluationSummary,
        ) = EvaluationSummary(
            hasTrue = left.hasTrue && right.hasTrue,
            hasFalse = left.hasFalse || (left.hasTrue && right.hasFalse),
            error = left.error ?: right.error.takeIf { left.hasTrue },
            hasPending = left.hasPending || (left.hasTrue && right.hasPending),
        )

        fun build(root: InclusionCondition): EvaluationNode {
            val pending = ArrayDeque<Pair<InclusionCondition, Boolean>>()
            pending += root to false
            while (pending.isNotEmpty()) {
                val (condition, expanded) = pending.removeLast()
                if (nodes.containsKey(condition)) continue
                if (!expanded && (condition is Conjunction || condition is Disjunction)) {
                    pending += condition to true
                    when (condition) {
                        is Conjunction -> {
                            pending += condition.right to false
                            pending += condition.left to false
                        }
                        is Disjunction -> {
                            pending += condition.right to false
                            pending += condition.left to false
                        }
                        else -> error("Only operators have evaluation children")
                    }
                    continue
                }

                val node = EvaluationNode(condition)
                when (condition) {
                    InclusionCondition.Always -> node.summary = EvaluationSummary(hasTrue = true)
                    InclusionCondition.Never -> node.summary = EvaluationSummary(hasFalse = true)
                    is InclusionCondition.Requires -> {
                        val evaluation =
                            async<Unit> {
                                val completion = try {
                                    EvaluationCompletion(node, Result.success(condition.include(binding)))
                                } catch (cause: Throwable) {
                                    EvaluationCompletion(node, Result.failure(cause), cause.takeUnless { it is Exception })
                                }
                                completions.send(completion)
                                completion.fatal?.let { throw it }
                            }
                        evaluations[node] = evaluation
                        node.summary = EvaluationSummary(hasPending = true)
                    }
                    is Conjunction -> {
                        node.left = nodes.getValue(condition.left)
                        node.right = nodes.getValue(condition.right)
                        parents.getOrPut(requireNotNull(node.left), ::mutableListOf) += node
                        parents.getOrPut(requireNotNull(node.right), ::mutableListOf) += node
                        node.summary = conjunction(requireNotNull(node.left).summary, requireNotNull(node.right).summary)
                    }
                    is Disjunction -> {
                        node.left = nodes.getValue(condition.left)
                        node.right = nodes.getValue(condition.right)
                        parents.getOrPut(requireNotNull(node.left), ::mutableListOf) += node
                        parents.getOrPut(requireNotNull(node.right), ::mutableListOf) += node
                        node.summary = disjunction(requireNotNull(node.left).summary, requireNotNull(node.right).summary)
                    }
                }
                nodes[condition] = node
            }
            return nodes.getValue(root)
        }

        fun propagate(changed: EvaluationNode) {
            val pending = ArrayDeque<EvaluationNode>()
            pending += parents[changed].orEmpty()
            while (pending.isNotEmpty()) {
                val node = pending.removeFirst()
                val updated =
                    when (node.condition) {
                        is Conjunction -> conjunction(requireNotNull(node.left).summary, requireNotNull(node.right).summary)
                        is Disjunction -> disjunction(requireNotNull(node.left).summary, requireNotNull(node.right).summary)
                        else -> error("Only operators have evaluation dependents")
                    }
                if (updated != node.summary) {
                    node.summary = updated
                    pending += parents[node].orEmpty()
                }
            }
        }

        suspend fun cancelRemaining() {
            evaluations.values.forEach { it.cancel() }
            evaluations.values.forEach { task ->
                try {
                    task.await()
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            }
        }

        val root = build(prunedCondition)
        while (true) {
            if (root.summary.hasTrue) {
                cancelRemaining()
                return@supervisorScope true
            }
            if (root.summary.hasPending) {
                val completion = completions.receive()
                completion.fatal?.let { throw it }
                val node = completion.node
                val result = completion.result
                evaluations.remove(node)
                node.summary =
                    result.fold(
                        onSuccess = { included -> EvaluationSummary(hasTrue = included, hasFalse = !included) },
                        onFailure = { cause -> EvaluationSummary(error = cause) },
                    )
                propagate(node)
                currentCoroutineContext().ensureActive()
                continue
            }
            cancelRemaining()
            root.summary.error?.let { throw it }
            return@supervisorScope false
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }
