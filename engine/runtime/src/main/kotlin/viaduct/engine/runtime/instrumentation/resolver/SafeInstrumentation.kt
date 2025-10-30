package viaduct.engine.runtime.instrumentation.resolver

/**
 * Helper object for safely executing instrumentation callbacks.
 * Catches any exceptions thrown by instrumentation to prevent them from breaking the main operation.
 */
internal object SafeInstrumentation {
    /**
     * Executes the given block and returns its result, or null if it throws an exception.
     * Instrumentation failures should not break the core operation.
     */
    inline fun <T> execute(block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            // Instrumentation failure shouldn't break the fetch operation
            null
        }
    }
}
