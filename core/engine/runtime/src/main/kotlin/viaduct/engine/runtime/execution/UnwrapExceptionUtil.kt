package viaduct.engine.runtime.execution

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException
import viaduct.engine.runtime.exceptions.FieldFetchingException
import viaduct.errors.TenantResolverException

object UnwrapExceptionUtil {
    fun unwrapExceptionForError(exception: Throwable): Throwable {
        return unwrapException(exception) { e ->
            isConcurrencyWrapper(e) || isViaductWrapper(e)
        }
    }

    /**
     * Unwraps exceptions based on a predicate, handling arbitrary nesting.
     * Continues unwrapping while the predicate returns true.
     */
    fun unwrapException(
        exception: Throwable,
        shouldUnwrap: (Throwable) -> Boolean
    ): Throwable {
        var cause = exception
        while (shouldUnwrap(cause)) {
            cause = cause.cause ?: break
        }
        return cause
    }

    fun isConcurrencyWrapper(e: Throwable) =
        e is CompletionException ||
            e is CancellationException ||
            e is ExecutionException ||
            e is InvocationTargetException

    fun isViaductWrapper(e: Throwable) = e is TenantResolverException || e is FieldFetchingException
}
