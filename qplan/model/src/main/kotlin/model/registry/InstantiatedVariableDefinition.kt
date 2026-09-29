package model.registry

import model.Arguments
import model.InclusionCondition
import model.ObjectEngineResult

/**
 * One variable instance and its occurrence-specific provider path.
 *
 * Equality is undefined. Binding identity belongs to [variable], not this read description.
 */
sealed interface InstantiatedFieldPathDefinition {
    val variable: Arguments.Variable
    val providerFragment: ProviderFragment
    val path: List<InstantiatedFieldPathElement>

    companion object {
        /** Returns a from-field definition for the instantiated [variable]. */
        fun of(
            variable: Arguments.Variable,
            providerFragment: ProviderFragment,
            path: List<InstantiatedFieldPathElement>,
        ): InstantiatedFieldPathDefinition {
            require(variable.isInstantiated) {
                "A from-field path definition requires an instantiated variable"
            }
            require(path.isNotEmpty()) { "A from-field path must be nonempty" }
            return InstantiatedFieldPathDefinitionImpl(
                variable = variable,
                providerFragment = providerFragment,
                path = path.toList(),
            )
        }
    }
}

/**
 * One provider-path step with its defining fragment's effective inclusion condition.
 * The condition includes ancestor guards and preserves correlations between repeated selections.
 * Equality is undefined.
 */
sealed interface InstantiatedFieldPathElement {
    val key: ObjectEngineResult.Key
    val inclusionCondition: InclusionCondition

    companion object {
        fun of(
            key: ObjectEngineResult.Key,
            inclusionCondition: InclusionCondition,
        ): InstantiatedFieldPathElement = InstantiatedFieldPathElementImpl(key, inclusionCondition)
    }
}

/**
 * One resolver-application variable instance and the resolver definition that supplies its value.
 *
 * Equality is structural: two definitions are equal exactly when their [variable] and [definition]
 * are equal.
 */
sealed interface VariableInstanceDefinition {
    val variable: Arguments.Variable
    val definition: VariableDefinition

    companion object {
        /** Returns a definition for one instantiated variable. */
        fun of(
            variable: Arguments.Variable,
            definition: VariableDefinition,
        ): VariableInstanceDefinition {
            require(variable.isInstantiated) {
                "A variable-instance definition requires an instantiated variable"
            }
            return VariableInstanceDefinitionImpl(variable, definition)
        }
    }
}

private class InstantiatedFieldPathDefinitionImpl(
    override val variable: Arguments.Variable,
    override val providerFragment: ProviderFragment,
    override val path: List<InstantiatedFieldPathElement>,
) : InstantiatedFieldPathDefinition

private class InstantiatedFieldPathElementImpl(
    override val key: ObjectEngineResult.Key,
    override val inclusionCondition: InclusionCondition,
) : InstantiatedFieldPathElement

private data class VariableInstanceDefinitionImpl(
    override val variable: Arguments.Variable,
    override val definition: VariableDefinition,
) : VariableInstanceDefinition
