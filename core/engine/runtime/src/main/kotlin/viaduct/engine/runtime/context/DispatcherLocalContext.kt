package viaduct.engine.runtime.context

import kotlinx.coroutines.CoroutineDispatcher

data class DispatcherLocalContext(val dispatcher: CoroutineDispatcher)
