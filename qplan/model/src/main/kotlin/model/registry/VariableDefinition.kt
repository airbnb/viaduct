package model.registry

import model.Arguments
import model.EngineInputData
import model.InclusionCondition
import model.MaterializeSelectionForest
import model.ObjectEngineResult
import model.guardedBy
import model.inputType
import viaduct.graphql.schema.ViaductSchema

/** The object- or Query-rooted input fragment that supplies a from-field variable. */
enum class ProviderFragment {
    OBJECT,
    QUERY,
}

/**
 * The source of one resolver-relative variable defined by a resolver fragment.
 *
 * Equality is structural: two definitions are equal exactly when they have the same variant and
 * equal [FromArgument.argument] and [FromArgument.inputPath], or equal [FromField.providerFragment]
 * and [FromField.path] and [FromField.responsePath], respectively.
 */
sealed interface VariableDefinition {
    /** A variable whose value is returned by its resolver function's one-shot variables provider. */
    data object FromProvider : VariableDefinition

    /** A field-target variable whose value is read from a path rooted at one resolver argument. */
    sealed interface FromArgument : VariableDefinition {
        val argument: ViaductSchema.FieldArg
        val inputPath: List<ViaductSchema.Field>

        companion object {
            /**
             * Returns the definition that reads [argument] followed by [inputPath].
             *
             * ### Invariant: from-argument-variable-definition-path-shape
             *
             * Every path component is a canonical field of the preceding input-object type. The
             * path never traverses a list.
             */
            fun of(
                argument: ViaductSchema.FieldArg,
                inputPath: List<ViaductSchema.Field> = emptyList(),
            ): FromArgument {
                var currentType = argument.inputType
                inputPath.forEach { field ->
                    require(!currentType.isList) {
                        "From-argument variable path cannot traverse list type $currentType"
                    }
                    val inputObject = currentType.baseTypeDef as? ViaductSchema.Input
                    require(
                        inputObject != null &&
                            field.containingDef == inputObject &&
                            inputObject.field(field.name) == field,
                    ) {
                        "From-argument variable path field ${field.containingDef.name}/" +
                            "${field.name} does not belong to $currentType"
                    }
                    currentType = field.inputType
                }
                return FromArgumentImpl(argument, inputPath.toList())
            }
        }

        /** Reads this definition from one resolved argument tuple. */
        fun read(arguments: Arguments.Resolved): EngineInputData?
    }

    /** A variable whose value is read from one path in one defining input fragment. */
    sealed interface FromField : VariableDefinition {
        val providerFragment: ProviderFragment
        val path: List<ObjectEngineResult.Key>
        val responsePath: List<String>

        /**
         * Compiles local guards for each path step, checking response-key containment and rejecting
         * statically excluded paths. Runtime exclusion is represented by a false evaluated guard.
         * Each occurrence's guard is carried into its own descendants before alternatives are
         * disjoined; unrelated aliases and other fragments cannot make this path included.
         */
        fun inclusionConditions(fragment: MaterializeSelectionForest): List<InclusionCondition> {
            var selections = fragment
            return path.mapIndexed { index, key ->
                val matches = selections.filter { it.responseKey == responsePath[index] }
                require(!matches.isEmpty() && matches.all { it.key == key }) {
                    "From-field response path must select its canonical key at every step"
                }
                val conditions = mutableListOf<InclusionCondition>()
                matches.forEach { conditions += it.inclusionCondition }
                val condition = InclusionCondition.anyOf(conditions)
                require(condition !== InclusionCondition.Never) {
                    "A from-field path cannot traverse a statically excluded selection"
                }
                selections = matches.flatMap { it.subselections.guardedBy(it.inclusionCondition) }
                condition
            }
        }

        companion object {
            /**
             * Returns the definition that reads [path] from [providerFragment].
             *
             * ### Invariant: from-field-variable-definition-path-shape
             *
             * [path] is nonempty, every nonterminal key selects a non-list composite value, and
             * the terminal key selects a simple value.
             */
            fun of(
                providerFragment: ProviderFragment,
                path: List<ObjectEngineResult.Key>,
                responsePath: List<String>,
            ): FromField {
                validateFieldPath(path, providerFragment)
                require(responsePath.size == path.size && responsePath.none(String::isBlank)) {
                    "A from-field response path must name every canonical path step"
                }
                return FromFieldImpl(providerFragment, path.toList(), responsePath.toList())
            }
        }
    }
}

private data class FromArgumentImpl(
    override val argument: ViaductSchema.FieldArg,
    override val inputPath: List<ViaductSchema.Field>,
) : VariableDefinition.FromArgument {
    override fun read(arguments: Arguments.Resolved): EngineInputData? {
        var value = arguments.fieldValues.getValue(argument.name)
        inputPath.forEach { field ->
            if (value == null) return null
            val fields = value as? Map<*, *>
                ?: error("From-argument variable path encountered non-object value $value")
            value = fields[field.name]
        }
        return value
    }
}

private data class FromFieldImpl(
    override val providerFragment: ProviderFragment,
    override val path: List<ObjectEngineResult.Key>,
    override val responsePath: List<String>,
) : VariableDefinition.FromField

private fun validateFieldPath(
    path: List<ObjectEngineResult.Key>,
    providerFragment: ProviderFragment,
) {
    val fragmentName = providerFragment.name.lowercase()
    require(path.isNotEmpty()) {
        "From-$fragmentName-field variable path must not be empty"
    }
    path.dropLast(1).forEach { key ->
        require(
            !key.field.type.isList &&
                key.field.type.baseTypeDef is ViaductSchema.CompositeTypeDef,
        ) {
            "From-$fragmentName-field variable path cannot cross list or simple field " +
                "${key.field.containingDef.name}/${key.field.name}"
        }
    }
    val terminal = path.last().field
    require(terminal.type.baseTypeDef is ViaductSchema.SimpleTypeDef) {
        "From-$fragmentName-field variable path must end at a simple field, not " +
            "${terminal.containingDef.name}/${terminal.name}"
    }
}
