package viaduct.remote.api.spi

import viaduct.remote.api.EncodedRemoteResolverContext
import viaduct.remote.api.RemoteResolverContextCaptureInput
import viaduct.remote.api.RemoteResolverContextException

/**
 * Captures host request context for remote resolver execution.
 *
 * Hosts should associate one capturer with each top-level request while request-scoped
 * dependencies are available. If the framework calls [capture] for multiple remote resolver
 * batches in that request, the capturer must reuse the same encoded snapshot.
 */
interface RemoteResolverContextCapturer {
    fun capture(input: RemoteResolverContextCaptureInput): EncodedRemoteResolverContext?

    companion object {
        val NO_OP: RemoteResolverContextCapturer =
            object : RemoteResolverContextCapturer {
                override fun capture(input: RemoteResolverContextCaptureInput): EncodedRemoteResolverContext? = null
            }
    }
}

/**
 * Resolves the [RemoteResolverContextCapturer] associated with the active top-level request.
 *
 * Long-lived remote resolver components retain this provider instead of retaining a capturer.
 * Implementations must return the same capturer throughout one top-level request.
 */
interface RemoteResolverContextCapturerProvider {
    fun get(): RemoteResolverContextCapturer

    companion object {
        val NO_OP: RemoteResolverContextCapturerProvider =
            object : RemoteResolverContextCapturerProvider {
                override fun get(): RemoteResolverContextCapturer = RemoteResolverContextCapturer.NO_OP
            }
    }
}

/**
 * Installs captured host request context for the complete lifetime of remote resolver execution.
 *
 * Implementations must restore the previous context after success, failure, or cancellation.
 * Implementations that reject malformed or unsupported encoded context should throw
 * [RemoteResolverContextException] before invoking the resolver block. Operational failures while
 * installing or restoring context should propagate using their original exception types.
 */
interface RemoteResolverContextApplier {
    suspend fun <T> apply(
        context: EncodedRemoteResolverContext?,
        block: suspend () -> T,
    ): T

    companion object {
        val NO_OP: RemoteResolverContextApplier =
            object : RemoteResolverContextApplier {
                override suspend fun <T> apply(
                    context: EncodedRemoteResolverContext?,
                    block: suspend () -> T,
                ): T = block()
            }
    }
}
