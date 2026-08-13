package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode

/**
 * Adapts `KSPLogger` calls to SLF4J-style `{}` placeholder formatting.
 *
 * The extractor keeps log messages in this format to stay aligned with the
 * `SLF4J_NON_TEMPLATED_LOGGING_RULE_INFO` validation while still using KSP's
 * logger APIs, which only accept fully-rendered strings.
 */
internal fun KSPLogger.loggingRegistryExtractor(
    message: String,
    vararg args: Any?,
) {
    logging("[RegistryExtractor] ${formatSlf4j(message, *args)}")
}

internal fun KSPLogger.warnRegistryExtractor(
    message: String,
    vararg args: Any?,
) {
    warn("[RegistryExtractor] ${formatSlf4j(message, *args)}")
}

internal fun KSPLogger.errorRegistryExtractor(
    message: String,
    vararg args: Any?,
) {
    error("[RegistryExtractor] ${formatSlf4j(message, *args)}")
}

internal fun KSPLogger.errorRegistryExtractor(
    message: String,
    symbol: KSNode,
) {
    error("[RegistryExtractor] $message", symbol)
}

private fun formatSlf4j(
    message: String,
    vararg args: Any?,
): String {
    if (args.isEmpty()) {
        return message
    }

    val builder = StringBuilder()
    var searchStart = 0
    var argIndex = 0

    while (true) {
        val placeholderIndex = message.indexOf("{}", startIndex = searchStart)
        if (placeholderIndex < 0) {
            builder.append(message.substring(searchStart))
            break
        }

        builder.append(message, searchStart, placeholderIndex)
        if (argIndex < args.size) {
            builder.append(renderArg(args[argIndex]))
            argIndex += 1
        } else {
            builder.append("{}")
        }
        searchStart = placeholderIndex + 2
    }

    if (argIndex < args.size) {
        builder.append(" [extra args: ")
        builder.append(args.drop(argIndex).joinToString(", ") { arg -> renderArg(arg) })
        builder.append(']')
    }

    return builder.toString()
}

private fun renderArg(arg: Any?): String {
    return when (arg) {
        null -> "null"
        is Throwable -> {
            val typeName = arg::class.qualifiedName ?: arg::class.simpleName ?: "Throwable"
            val message = arg.message
            if (message.isNullOrBlank()) {
                typeName
            } else {
                "$typeName: $message"
            }
        }
        else -> arg.toString()
    }
}
