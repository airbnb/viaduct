package model

import model.registry.ResolverTarget
import model.registry.ResolverTarget.FieldValueResolverTarget
import model.registry.render
import viaduct.graphql.schema.ViaductSchema

/**
 * One schema-checked output-field argument tuple.
 *
 * A tuple may be ground, may recursively contain [Variable] expressions, or may be a
 * resolver-registry [Template]. Equality is structural over the represented argument expressions,
 * including each variable's resolver target, name, instance ID, and recursive expression
 * position. Equality and hashing never inspect a variable's eventual binding.
 *
 * ### Invariant: arguments-schema-canonicality
 *
 * Every schema definition carried by an argument tuple is the canonical definition from the
 * [Assumptions.schema] under which that tuple is interpreted.
 */
sealed interface Arguments {
    /** A fully grounded argument outcome. */
    sealed interface Ground : Arguments

    /**
     * The successfully resolved values supplied for one output field's complete argument
     * definition.
     *
     * This tuple is ground and inspectable. Equality is structural over its field values.
     */
    sealed interface Resolved : Ground {
        val fieldValues: EngineInputObjectData

        companion object {
            /**
             * ### Invariant: resolved-arguments-factory-schema-conformance
             *
             * Every result recursively conforms to [field]'s argument definition. Declared
             * defaults are materialized for arguments absent from [fields].
             */
            fun of(
                field: ViaductSchema.Field,
                fields: Map<String, Any?>,
            ): Resolved {
                val arguments = Arguments.of(field, fields)
                require(arguments is Resolved) {
                    "Resolved arguments cannot contain variables or errors"
                }
                return arguments
            }
        }
    }

    /** The whole argument tuple is erroneous and has no individual argument values. */
    data object Error : Ground

    /**
     * An argument tuple in a resolver-registry template.
     *
     * Every recursively contained variable is a [Variable.isTemplate]. Instantiation replaces
     * every contained variable with one owned by the same [ResolverOccurrenceId].
     */
    sealed interface Template : Arguments {
        /**
         * Returns this argument template instantiated for [resolverOccurrenceId] and checked against
         * [expectedField].
         */
        fun instantiate(
            expectedField: ViaductSchema.Field,
            resolverOccurrenceId: ResolverOccurrenceId,
        ): Arguments

        companion object {
            /**
             * Wraps [arguments] as a registry argument template checked against [expectedField].
             *
             * [arguments] may contain only variable templates, never variable instances.
             */
            fun of(
                expectedField: ViaductSchema.Field,
                arguments: Arguments,
            ): Template = argumentTemplateOf(expectedField, arguments)
        }
    }

    /**
     * Identifier of an execution variable used in argument expressions.
     *
     * Registry variables are templates. During resolution, templates are instantiated for their
     * owning resolver occurrence.
     */
    sealed interface Variable {
        val target: ResolverTarget
        val variableName: String

        /** Whether this is the registry template rather than an occurrence-specific variable. */
        val isTemplate: Boolean

        /** The variable-instance identity, or null for a registry template. */
        val instanceId: VariableInstanceId?

        val isInstantiated: Boolean
            get() = instanceId != null

        /** Returns this variable template instantiated for [resolverOccurrenceId]. */
        fun instantiate(resolverOccurrenceId: ResolverOccurrenceId): Variable {
            require(isTemplate) { "Only variable templates can be instantiated" }
            return InstanceVariableImpl(
                variableName = variableName,
                target = target,
                instanceId =
                    VariableInstanceId.of(
                        resolverOccurrenceId = resolverOccurrenceId,
                        resolverTarget = target,
                        variableName = variableName,
                    ),
            )
        }

        companion object {
            /**
             * Returns the template named [variableName] defined by [target]. Equal arguments yield
             * equal templates.
             */
            fun of(
                target: ResolverTarget,
                variableName: String,
            ): Variable = TemplateVariableImpl(variableName, target)

            /** Returns a field-value-resolver template for compatibility with field-only callers. */
            fun of(
                field: ViaductSchema.ObjectField,
                variableName: String,
            ): Variable = of(FieldValueResolverTarget(field), variableName)
        }
    }

    companion object {
        /**
         * Constructs the schema-checked argument tuple for [field].
         *
         * Declared defaults are included. A variable-free result is either [Resolved] or [Error]
         * when any supplied expression recursively contains [ArgumentResolutionError].
         */
        fun of(
            field: ViaductSchema.Field,
            fields: Map<String, Any?>,
        ): Arguments = argumentsOf(field, fields)
    }
}

/**
 * One erroneous argument expression.
 *
 * Recursive argument construction collapses any occurrence of this sentinel to [Arguments.Error].
 * It does not belong to [EngineInputData].
 */
data object ArgumentResolutionError

private data class ResolvedArgumentsImpl(
    override val fieldValues: EngineInputObjectData,
) : Arguments.Resolved

private val emptyResolvedArguments = ResolvedArgumentsImpl(emptyMap())

private data class TemplateVariableImpl(
    override val variableName: String,
    override val target: ResolverTarget,
) : Arguments.Variable {
    override val isTemplate: Boolean
        get() = true

    override val instanceId: VariableInstanceId?
        get() = null

    override fun toString(): String =
        "Variable.Template(" +
            "name=$variableName, " +
            "target=${target.render()}" +
            ")"
}

private data class InstanceVariableImpl(
    override val variableName: String,
    override val target: ResolverTarget,
    override val instanceId: VariableInstanceId,
) : Arguments.Variable {
    override val isTemplate: Boolean
        get() = false

    override fun toString(): String =
        "Variable.Instance(" +
            "name=$variableName, " +
            "target=${target.render()}, " +
            "id=$instanceId" +
            ")"
}

internal fun argumentsOfGround(fields: EngineInputObjectData): Arguments.Resolved =
    if (fields.isEmpty()) {
        emptyResolvedArguments
    } else {
        ResolvedArgumentsImpl(fields)
    }
