package viaduct.ksp.validation

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode
import java.text.MessageFormat

/**
 * KSPLogger extensions for ResolverSelectionSetProcessor log messages.
 *
 * KSP's logger accepts only fully-rendered strings, so MessageFormat patterns are
 * rendered here before the string is handed off to the logger.
 */
internal fun KSPLogger.loggingResolverProcessor(
    message: String,
    vararg args: Any?,
) {
    logging("[ResolverSelectionSetProcessor] ${MessageFormat.format(message, *args)}")
}

internal fun KSPLogger.errorResolverProcessor(
    message: String,
    vararg args: Any?,
) {
    error("[ResolverSelectionSetProcessor] ${MessageFormat.format(message, *args)}")
}

internal fun KSPLogger.errorResolverProcessor(
    message: String,
    symbol: KSNode,
) {
    error("[ResolverSelectionSetProcessor] $message", symbol)
}
