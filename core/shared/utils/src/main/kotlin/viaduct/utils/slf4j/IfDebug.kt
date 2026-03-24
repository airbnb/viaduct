package viaduct.utils.slf4j

import org.slf4j.Logger

/**
 * Run the block if the logger is in debug mode.
 */
inline fun Logger.ifDebug(block: Logger.() -> Unit) {
    if (isDebugEnabled) {
        block()
    }
}
