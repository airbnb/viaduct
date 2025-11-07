package viaduct.engine.api

import kotlinx.coroutines.CancellationException

/**
 * Indicates that a request scope has been cancelled. If cause is null, the request scope was cancelled gracefully.
 */
class RequestScopeCancellationException(message: String, override val cause: Throwable? = null) : CancellationException(message)
