package viaduct.engine.runtime2.model

/**
 * The outcome bound to one execution variable.
 *
 * Equality is structural. [Input.value] is an ordinary error-free input value or null; [Error]
 * records that evaluating the variable failed without placing an error inside the input domain.
 */
sealed interface VariableBinding {
    sealed interface Input : VariableBinding {
        val value: EngineInputData?
    }

    data object Error : VariableBinding

    companion object {
        /**
         * Constructs a successful variable binding.
         */
        fun of(value: EngineInputData?): Input = InputVariableBindingImpl(value)
    }
}

private data class InputVariableBindingImpl(
    override val value: EngineInputData?,
) : VariableBinding.Input
