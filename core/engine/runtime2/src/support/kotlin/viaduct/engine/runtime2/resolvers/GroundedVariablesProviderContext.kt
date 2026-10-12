package viaduct.engine.runtime2.resolvers

import kotlin.coroutines.CoroutineContext

internal interface GroundedVariablesProviderContext {
    val variablesProviderContext: CoroutineContext
}
