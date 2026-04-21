package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode

internal class RecordingKspLogger : KSPLogger {
    val infos = mutableListOf<String>()
    val warns = mutableListOf<String>()
    val errors = mutableListOf<String>()

    override fun logging(
        message: String,
        symbol: KSNode?,
    ) {
        infos += message
    }

    override fun info(
        message: String,
        symbol: KSNode?,
    ) {
        infos += message
    }

    override fun warn(
        message: String,
        symbol: KSNode?,
    ) {
        warns += message
    }

    override fun error(
        message: String,
        symbol: KSNode?,
    ) {
        errors += message
    }

    override fun exception(e: Throwable) {
        errors += e.toString()
    }
}
