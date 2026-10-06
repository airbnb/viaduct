package viaduct.engine.runtime2.resolution.framework

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.EngineResultCell
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult

/** Awaits ordinary payload work without following distinguished parent backedges. */
internal suspend fun EngineResultCell.awaitMutationPayloadCompletion() {
    awaitMutationPayloadCompletion(Collections.newSetFromMap(IdentityHashMap()))
}

private suspend fun EngineResultCell.awaitMutationPayloadCompletion(seen: MutableSet<ObjectEngineResult>) {
    if (!fetchActivated()) return
    val result = value.await()
    try {
        fieldCheckerResult.await()
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        // Checker failures remain local result failures; sibling mutations still run.
    }
    result.awaitMutationPayloadCompletion(seen)
}

private suspend fun EngineResult?.awaitMutationPayloadCompletion(seen: MutableSet<ObjectEngineResult>) {
    when (this) {
        is ObjectEngineResult -> if (seen.add(this)) {
            try {
                typeCheckerResult.await()
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
            }
            keys.filterNot { it is ObjectEngineResult.ParentKey }.forEach { getCell(it).awaitMutationPayloadCompletion(seen) }
        }
        is ListEngineResult -> forEach { it.awaitMutationPayloadCompletion(seen) }
    }
}
