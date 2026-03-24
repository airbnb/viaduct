package viaduct.engine.api.fragment

import viaduct.engine.api.fragment.errors.FragmentFieldEngineResolutionError

data class FragmentFieldEngineResolutionResult(
    val data: Map<String, Any?>,
    val errors: List<FragmentFieldEngineResolutionError> = listOf()
) {
    companion object {
        val empty: FragmentFieldEngineResolutionResult =
            FragmentFieldEngineResolutionResult(emptyMap(), emptyList())
    }
}
