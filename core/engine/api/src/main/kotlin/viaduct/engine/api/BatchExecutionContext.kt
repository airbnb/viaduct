package viaduct.engine.api

import viaduct.apiannotations.InternalApi

@InternalApi
interface BatchExecutionContext {
    /** Returns the captured context, failing if the selector was never captured. */
    fun invocationContextFor(selector: Any): EngineExecutionContext
}

@InternalApi
fun EngineExecutionContext.invocationContextFor(selector: Any): EngineExecutionContext = (this as? BatchExecutionContext)?.invocationContextFor(selector) ?: this
