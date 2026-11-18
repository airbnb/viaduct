package viaduct.engine.runtime2.resolution.framework

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Promise
import viaduct.errors.isJvmSignal

internal suspend inline fun publishCheckerResult(
    destination: Promise<CheckerResult?>,
    onFailure: () -> Unit = {},
    invoke: () -> CheckerResult?,
): Boolean {
    val result = try {
        invoke()
    } catch (cause: Exception) {
        if (cause.isJvmSignal()) throw cause
        currentCoroutineContext().ensureActive()
        onFailure()
        check(destination.fail(cause)) { "Checker failure was published twice" }
        return false
    }
    check(destination.complete(result)) { "Checker result was completed twice" }
    return result !is CheckerResult.Error
}
