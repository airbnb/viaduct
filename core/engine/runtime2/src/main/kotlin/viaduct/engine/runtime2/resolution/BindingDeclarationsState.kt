package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise

/** Resolution readiness state for the binding domain of each object-result occurrence. */
internal class BindingDeclarationsState {
    private val declarationsByObject =
        ConcurrentHashMap<ObjectEngineResult, Promise<Unit>>()

    suspend fun awaitBindingsDeclared(target: ObjectEngineResult) {
        declarationsByObject
            .computeIfAbsent(target) { Promise.ofDeferred() }
            .await()
    }

    fun markBindingsDeclared(target: ObjectEngineResult) {
        check(
            declarationsByObject
                .computeIfAbsent(target) { Promise.ofDeferred() }
                .complete(Unit),
        ) { "Resolution bindings were declared twice for one object occurrence" }
    }
}
