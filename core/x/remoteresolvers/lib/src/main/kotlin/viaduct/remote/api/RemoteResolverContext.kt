package viaduct.remote.api

/**
 * Transport-independent representation of request context captured for remote resolver execution.
 *
 * The remote-resolver transport treats the payload as opaque. Hosts define the format, encode the
 * payload on the caller, and decode and install it on the remote server.
 *
 * @property format Host-defined identifier for the payload encoding. Must not be blank.
 * @property version Positive host-defined version of [format].
 */
class EncodedRemoteResolverContext(
    val format: String,
    val version: Int,
    payload: ByteArray,
) {
    private val encodedPayload = payload.copyOf()

    init {
        require(format.isNotBlank()) { "Remote resolver context format must not be blank" }
        require(version > 0) { "Remote resolver context version must be positive" }
    }

    /** Returns a copy so callers cannot mutate context after it has been captured. */
    val payload: ByteArray
        get() = encodedPayload.copyOf()
}

/**
 * Framework-owned input for capturing host request context.
 *
 * The input is intentionally empty until the framework defines which request data should be
 * available to capturers. New request-level inputs can be added without changing the capture
 * contract.
 */
class RemoteResolverContextCaptureInput internal constructor() {
    companion object {
        /** Empty capture input used until the framework defines request-level fields. */
        val EMPTY = RemoteResolverContextCaptureInput()
    }
}

/**
 * Indicates that caller-provided remote resolver context is malformed, unsupported, or
 * undecodable.
 */
class RemoteResolverContextException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
