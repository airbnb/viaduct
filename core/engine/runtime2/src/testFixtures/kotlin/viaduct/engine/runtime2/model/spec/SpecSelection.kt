package viaduct.engine.runtime2.model.spec

import viaduct.engine.api.FieldDirectives
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.graphql.schema.ViaductSchema

/**
 * A post-validation selection in a GraphQL-spec selection set.
 *
 * This model retains the recursive shape of GraphQL selections: fields descend into their result
 * values, while inline fragments add nested type conditions without descending. Named fragment
 * spreads are absent because modeled inputs have already inlined them. `@skip` and `@include`
 * directives are lowered into [inclusionCondition].
 *
 * ### Invariant: spec-selection-well-foundedness
 *
 * A selection and its nested selections form a finite, well-founded value.
 *
 * ### Equality
 *
 * Kotlin `equals` is currently undefined for [SpecSelection]. The model does not yet assume that
 * spec selections can or need to be compared.
 */
sealed interface SpecSelection {
    val inclusionCondition: InclusionCondition

    /**
     * A GraphQL field selection.
     *
     * The field is valid in its surrounding post-validation type context. [fieldName] and
     * [arguments] identify the selected schema field invocation; [alias] affects its GraphQL
     * response key but not the field invocation.
     */
    sealed interface Field : SpecSelection {
        /** The response alias, or null when the response key is [fieldName]. */
        val alias: String?

        /** The canonical schema field selected at this source occurrence. */
        val schemaField: ViaductSchema.Field

        /** The schema field name. */
        val fieldName: String
            get() = schemaField.name

        /**
         * The schema-checked field argument tuple.
         *
         * ### Invariant: spec-field-arguments
         *
         * Declared defaults have been applied, every required argument is present, and every
         * non-variable expression recursively conforms to its argument type.
         */
        val arguments: Arguments

        /** Generic directives applied to this source field occurrence, when available. */
        val fieldDirectives: FieldDirectives?

        /**
         * The selection set on this field's result.
         *
         * ### Invariant: spec-field-shape
         *
         * This is null exactly when the field's base type is a [ViaductSchema.SimpleTypeDef]. When the base
         * type is a [ViaductSchema.CompositeTypeDef], this is non-null. It may be empty after an external
         * operation's `__typename` selections have been erased.
         */
        val subselections: List<SpecSelection>?

        companion object {
            /**
             * Constructs a field selection whose subselection shape matches [field]'s base type.
             *
             * @throws IllegalArgumentException when a simple field has subselections or a composite
             * field lacks a selection set
             */
            @JvmStatic
            fun of(
                alias: String?,
                field: ViaductSchema.Field,
                arguments: Map<String, Any?>,
                subselections: List<SpecSelection>?,
                inclusionCondition: InclusionCondition = InclusionCondition.Always,
                fieldDirectives: FieldDirectives? = null,
            ): Field {
                when (field.type.baseTypeDef) {
                    is ViaductSchema.SimpleTypeDef ->
                        require(subselections == null) {
                            "Simple field ${field.containingDef.name}.${field.name} " +
                                "must not have subselections"
                        }

                    is ViaductSchema.CompositeTypeDef ->
                        require(subselections != null) {
                            "Composite field ${field.containingDef.name}.${field.name} " +
                                "requires a selection set"
                        }
                }
                return FieldImpl(
                    alias,
                    field,
                    Arguments.of(field, arguments),
                    subselections,
                    inclusionCondition,
                    fieldDirectives,
                )
            }
        }
    }

    /**
     * A GraphQL inline fragment.
     *
     * This node does not descend into the object-value tree. It only nests [selections] beneath an
     * optional type condition and an inclusion condition.
     */
    sealed interface InlineFragment : SpecSelection {
        /**
         * The fragment's canonical composite type condition, or null when it has no type condition.
         *
         * ### Invariant: spec-inline-fragment-applicability
         *
         * A non-null condition belongs to the canonical schema and is valid in the surrounding
         * post-validation type context.
         *
         * ### Interpretation
         *
         * A null condition leaves the surrounding type condition unchanged. A non-null condition is
         * a definition in the canonical schema.
         */
        val typeCondition: ViaductSchema.CompositeTypeDef?

        /**
         * ### Invariant: spec-inline-fragment-shape
         *
         * The non-empty, ordered selection set contained by this inline fragment.
         */
        val selections: List<SpecSelection>

        companion object {
            /**
             * Constructs an inline fragment with a non-empty selection set.
             *
             * @throws IllegalArgumentException when [selections] is empty
             */
            @JvmStatic
            fun of(
                typeCondition: ViaductSchema.CompositeTypeDef?,
                selections: List<SpecSelection>,
                inclusionCondition: InclusionCondition = InclusionCondition.Always,
            ): InlineFragment {
                require(selections.isNotEmpty()) {
                    "Inline fragment requires a non-empty selection set"
                }
                return InlineFragmentImpl(typeCondition, selections, inclusionCondition)
            }
        }
    }
}

private class FieldImpl(
    override val alias: String?,
    override val schemaField: ViaductSchema.Field,
    override val arguments: Arguments,
    override val subselections: List<SpecSelection>?,
    override val inclusionCondition: InclusionCondition,
    override val fieldDirectives: FieldDirectives?,
) : SpecSelection.Field

private class InlineFragmentImpl(
    override val typeCondition: ViaductSchema.CompositeTypeDef?,
    override val selections: List<SpecSelection>,
    override val inclusionCondition: InclusionCondition,
) : SpecSelection.InlineFragment
