package viaduct.engine.runtime2.model

import java.util.IdentityHashMap
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

/**
 * Returns whether two completed result trees contain the same values and checker results.
 *
 * This explicit extensional comparison is distinct from ordinary equality because
 * [EngineResultCell] and [ObjectEngineResult] use reference equality. Symbolic object keys compare
 * variable occurrences by their root-relative addresses so independently rooted executions of the
 * same construction can compare extensionally; ordinary key and variable equality remains fully
 * root-qualified. Both trees must be finite and every present promise they contain must be
 * completed. Its result is meaningful only after both trees are quiescent; the comparison does not
 * take an atomic snapshot while promises or cells are being mutated concurrently.
 * Two completed checker errors compare extensionally as errors without invoking their externally
 * supplied equality.
 * Extra fields can make results compare differently even when their client-selected projections agree.
 *
 * @throws UncompletedPromiseException when either tree contains an uncompleted promise
 */
fun EngineResult?.sameCompletedResultAs(other: EngineResult?): Boolean {
    val same = CompletedResultComparison().same(this, other)
    if (!same) {
        requireCompleted()
        other.requireCompleted()
    }
    return same
}

private class CompletedResultComparison {
    private val rightByLeft = IdentityHashMap<ObjectEngineResult, ObjectEngineResult>()
    private val leftByRight = IdentityHashMap<ObjectEngineResult, ObjectEngineResult>()

    fun same(
        left: EngineResult?,
        right: EngineResult?,
    ): Boolean {
        if (left == null || right == null) return left == right

        return when (left) {
            is ErrorEngineResult -> right is ErrorEngineResult
            is ListEngineResult ->
                right is ListEngineResult &&
                    left.typeExpr == right.typeExpr &&
                    left.size == right.size &&
                    left.indices.all { index -> sameCell(left[index], right[index]) }
            is ObjectEngineResult -> right is ObjectEngineResult && sameObject(left, right)
            else -> left.isScalarResultMember() && right.isScalarResultMember() && left == right
        }
    }

    fun sameCell(
        left: EngineResultCell,
        right: EngineResultCell,
    ): Boolean =
        same(left.value.get(), right.value.get()) &&
            left.fieldCheckerResult.get().hasSameCompletedCheckerResultAs(
                right.fieldCheckerResult.get(),
            )

    fun sameParentCell(
        left: EngineResultCell,
        right: EngineResultCell,
    ): Boolean {
        val leftValue = left.value.get()
        val rightValue = right.value.get()
        val sameValue =
            when {
                leftValue == null || rightValue == null -> leftValue == null && rightValue == null
                leftValue is ObjectEngineResult && rightValue is ObjectEngineResult ->
                    rightByLeft[leftValue] === rightValue && leftByRight[rightValue] === leftValue
                else -> false
            }
        return sameValue &&
            left.fieldCheckerResult.get().hasSameCompletedCheckerResultAs(
                right.fieldCheckerResult.get(),
            )
    }

    private fun sameObject(
        left: ObjectEngineResult,
        right: ObjectEngineResult,
    ): Boolean {
        rightByLeft[left]?.let { mapped -> return mapped === right }
        if (leftByRight.containsKey(right)) return false
        rightByLeft[left] = right
        leftByRight[right] = left
        return left.sameCompletedObjectResultAs(right, this)
    }
}

private fun CheckerResult?.hasSameCompletedCheckerResultAs(other: CheckerResult?): Boolean =
    when {
        this is CheckerResult.Error -> other is CheckerResult.Error
        this === CheckerResult.Success -> other === CheckerResult.Success
        else -> other == null
    }

/**
 * The result is meaningful only after both object trees are quiescent. Store snapshots and
 * recursive reads do not form one atomic snapshot while promises or cells are being mutated.
 */
private fun ObjectEngineResult.sameCompletedObjectResultAs(
    other: ObjectEngineResult,
    comparison: CompletedResultComparison,
): Boolean {
    val leftCells = completedCells
    val rightCells = other.completedCells
    if (
        type != other.type ||
        leftCells.size != rightCells.size ||
        !typeCheckerResult.get().hasSameCompletedCheckerResultAs(
            other.typeCheckerResult.get(),
        )
    ) {
        return false
    }

    val unmatchedRightCells = rightCells.entries.toMutableList()
    return leftCells.all { (leftKey, leftCell) ->
        val matchIndex =
            unmatchedRightCells.indexOfFirst { (rightKey, _) ->
                leftKey.hasSameRootRelativeStructureAs(rightKey)
            }
        if (matchIndex < 0) {
            false
        } else {
            val rightCell = unmatchedRightCells.removeAt(matchIndex).value
            if (leftKey is ObjectEngineResult.ParentKey) {
                comparison.sameParentCell(leftCell, rightCell)
            } else {
                comparison.sameCell(leftCell, rightCell)
            }
        }
    }
}

private fun EngineResult?.requireCompleted() {
    when (this) {
        null,
        is ErrorEngineResult,
        -> Unit
        is ListEngineResult ->
            forEach { cell ->
                cell.fieldCheckerResult.get()
                cell.value.get().requireCompleted()
            }
        is ObjectEngineResult -> {
            typeCheckerResult.get()
            completedCells.forEach { (key, cell) ->
                if (key !is ObjectEngineResult.ParentKey) cell.value.get().requireCompleted()
            }
        }
        else -> check(isScalarResultMember()) { "Value is not an engine result: $this" }
    }
}

private fun EngineResult.isScalarResultMember(): Boolean = scalarResultTypeNameOrNull() != null || this is ViaductSchema.EnumValue
