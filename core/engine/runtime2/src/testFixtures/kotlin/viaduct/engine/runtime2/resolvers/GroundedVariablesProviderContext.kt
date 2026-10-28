package viaduct.engine.runtime2.resolvers

import kotlin.coroutines.CoroutineContext

interface GroundedVariablesProviderContext {
    val variablesProviderContext: CoroutineContext
}
