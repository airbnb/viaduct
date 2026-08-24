package viaduct.engine.api.spi

/**
 * Terminates the attempt to load one tenant module without terminating the others: the engine catches
 * this per-module while assembling the dispatcher registry, logs it, and continues.
 */
class TenantModuleException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
