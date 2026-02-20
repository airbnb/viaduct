package viaduct.invariants

import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.invariants.FailureCollector.Companion.EMPTY_ARGS

suspend fun <T> FailureCollector.doesThrowSuspendVersion(
    expectedClass: Class<T>,
    message: String,
    body: suspend () -> Unit
): Boolean {
    @Suppress("Detekt.TooGenericExceptionCaught")
    try {
        body()
        addFailure(
            "Expected exception of type " + expectedClass.name + " but no exception was thrown.",
            message,
            EMPTY_ARGS
        )
        return false
    } catch (t: Throwable) {
        if (t is CancellationException) currentCoroutineContext().ensureActive()
        var unwrappedException: Throwable? = t
        // InvocationTargetException is thrown by Method.invoke when the method invoked
        // throws an exception - we unwrap the exception thrown because that's the one
        // we're likely wanting to test against.
        if (t is InvocationTargetException) {
            unwrappedException = t.cause
        }
        if (expectedClass.isInstance(unwrappedException)) return true
        addFailure(
            "Expected exception of type " +
                expectedClass.name +
                " but got " +
                unwrappedException?.javaClass?.name +
                ".",
            message,
            EMPTY_ARGS
        )
        return false
    }
}
