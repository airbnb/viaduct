package viaduct.engine.runtime.execution

import graphql.Directives.IncludeDirective
import graphql.Directives.SkipDirective
import graphql.execution.CoercedVariables
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.VariableReference
import graphql.schema.GraphQLObjectType
import viaduct.utils.collections.MaskedSet

/**
 * Constraints models some different ways that GraphQL field execution can
 * be logically constrained, and can be used to deduce if we can skip
 * execution for a field.
 *
 * These are used as part of collection to drop any possible fields.
 */
interface Constraints {
    /** the result of solving a [Constraints] */
    enum class Resolution {
        /**
         * [Constraints] have been solved in a way that we can determine
         * that this selection will never be executed and can be dropped.
         * Examples are unsatisfiable type constraints, or directives
         * that exclude execution.
         */
        Drop,

        /**
         * [Constraints] have been solved in a way that we can determine
         * that this selection will always be executed and can be collected.
         * Examples are a selection that has no type constraints or
         * conditional directives.
         */
        Collect,

        /**
         * [Constraints] could not be conclusively resolved to [Drop] or [Collect],
         * due to insufficient bounds.
         *
         * Examples are a selection with a skip directive that depends on
         * an unknown variable value, or a type condition that depends on an
         * unknown runtime type.
         */
        Unsolved
    }

    data class Ctx(val variables: CoercedVariables?, val parentTypes: MaskedSet<GraphQLObjectType>) {
        companion object {
            val empty: Ctx = Ctx(null, MaskedSet.empty())
        }
    }

    /** Solve the current Constraints using the provided [Ctx] */
    fun solve(ctx: Ctx): Resolution

    /** narrow the current type constraints to be bounded by the provided [possibleTypes] */
    fun narrowTypes(possibleTypes: MaskedSet<GraphQLObjectType>): Constraints

    /** add a new constraint based on the provided [directive] */
    fun withDirective(directive: Directive): Constraints

    /** add multiple directive constraints */
    fun withDirectives(directives: List<Directive>): Constraints = directives.fold(this) { acc, d -> acc.withDirective(d) }

    private sealed interface IfValue {
        object True : IfValue

        object False : IfValue

        @JvmInline
        value class Variable(val name: String) : IfValue
    }

    // default semantics are for skip, a ConditionalDirective that models @include should set isSkip=false
    private data class ConditionalDirective(val ifValue: IfValue, val isSkip: Boolean = true) {
        val variableName: String? get() = (ifValue as? IfValue.Variable)?.name

        fun solve(variables: CoercedVariables?): Resolution =
            when (ifValue) {
                is IfValue.Variable -> {
                    val varValue = variables?.get(ifValue.name) as? Boolean
                    if (varValue == null) {
                        Resolution.Unsolved
                    } else {
                        solve(varValue)
                    }
                }
                else -> solve(ifValue == IfValue.True)
            }

        private fun solve(skipIfValue: Boolean): Resolution =
            if (skipIfValue xor isSkip) {
                Resolution.Collect
            } else {
                Resolution.Drop
            }

        companion object {
            fun fromDirective(dir: Directive): ConditionalDirective? =
                when (dir.name) {
                    SkipDirective.name, IncludeDirective.name -> {
                        val ifValue = when (val v = dir.argumentsByName["if"]!!.value) {
                            is BooleanValue -> if (v.isValue) IfValue.True else IfValue.False
                            is VariableReference -> IfValue.Variable(v.name)
                            else ->
                                throw IllegalArgumentException(
                                    "`ifValue` must be either a BooleanValue or VariableReference"
                                )
                        }

                        val isSkip = dir.name == SkipDirective.name
                        ConditionalDirective(ifValue, isSkip)
                    }
                    else -> null
                }
        }
    }

    companion object {
        /** A [Constraints] that will always solve with [Resolution.Collect] */
        val Unconstrained: Constraints = object : Constraints {
            override fun solve(ctx: Ctx): Resolution = Resolution.Collect

            override fun narrowTypes(possibleTypes: MaskedSet<GraphQLObjectType>): Constraints = Impl(directives = emptyList(), possibleTypes = possibleTypes)

            override fun withDirective(directive: Directive): Constraints = Constraints(listOf(directive), null)
        }

        val Drop: Constraints = object : Constraints {
            override fun solve(ctx: Ctx): Resolution = Resolution.Drop

            override fun narrowTypes(possibleTypes: MaskedSet<GraphQLObjectType>): Constraints = this

            override fun withDirective(directive: Directive): Constraints = this
        }

        /**
         * A simple implementation of [Constraints].
         *
         * @param possibleTypes all possible types for which the type constraint will solve
         *  as [Resolution.Collect].
         *
         *  An empty value indicates that there are no satisfiable type conditions, while a null value
         *  indicates that all type conditions will satisfy this constraint.
         */
        private data class Impl(
            val directives: List<ConditionalDirective>,
            val possibleTypes: MaskedSet<GraphQLObjectType>?
        ) : Constraints {
            override fun solve(ctx: Ctx): Resolution {
                val typeResolution = solveTypes(ctx)

                val resolutions = directives.fold(setOf(typeResolution)) { acc, d ->
                    acc + d.solve(ctx.variables)
                }

                return if (Resolution.Drop in resolutions) {
                    Resolution.Drop
                } else if (Resolution.Unsolved in resolutions) {
                    Resolution.Unsolved
                } else {
                    Resolution.Collect
                }
            }

            private fun solveTypes(ctx: Ctx): Resolution {
                // a null possibleTypes signals no type constraints
                if (possibleTypes == null) return Resolution.Collect

                if (ctx.parentTypes.isEmpty()) return Resolution.Collect

                // check the intersection of this.possibleTypes with ctx.parentTypes
                // We can Collect if every ctx.parentType is in this.possibleTypes,
                // and we can Drop if no ctx.parentType is in this.possibleTypes
                // otherwise, the type constraint is unsolvable.
                // This code is perf sensitive!
                val typeMatches = ctx.parentTypes.intersect(possibleTypes).size

                return when (typeMatches) {
                    // if no context types can satisfy the type constraints, then drop
                    0 -> Resolution.Drop

                    // if all context types are in possibleTypes, then collect
                    ctx.parentTypes.size -> Resolution.Collect

                    // if we get to this case, then the runtime possible type could be either inside
                    // or outside our type constraints
                    else -> Resolution.Unsolved
                }
            }

            override fun narrowTypes(possibleTypes: MaskedSet<GraphQLObjectType>): Constraints =
                if (this.possibleTypes == null) {
                    // A null value for `this.possibleTypes` indicates no type constraints.
                    // In this case, narrowing to the provided possibleTypes is the same as
                    // copying them.
                    copy(possibleTypes = possibleTypes)
                } else {
                    val newPossibleTypes = this.possibleTypes.intersect(possibleTypes)
                    if (newPossibleTypes.isEmpty()) {
                        // if narrowing to the provided possibleTypes produces an empty TypeSet, then
                        // there are no types that satisfy our type constraints and we can
                        // always drop
                        Drop
                    } else if (newPossibleTypes == this.possibleTypes) {
                        this
                    } else {
                        copy(possibleTypes = newPossibleTypes)
                    }
                }

            override fun withDirective(directive: Directive): Constraints {
                val cd = ConditionalDirective.fromDirective(directive) ?: return this

                // if this directive solves to Drop, then the entire Constraints solves to Drop
                if (cd.solve(null) == Resolution.Drop) {
                    return Drop
                }

                cd.variableName?.let { varName ->
                    // if any directives exist with the same variable name but a reversed boolean,
                    // we can always drop
                    if (directives.any { it.variableName == varName && it.isSkip != cd.isSkip }) {
                        return Drop
                    }
                }
                return copy(directives = directives + cd)
            }
        }

        /** create a new [Constraints] derived from the provided properties */
        operator fun invoke(
            directives: List<Directive>,
            possibleTypes: Collection<GraphQLObjectType>
        ): Constraints = Constraints(directives, MaskedSet(possibleTypes))

        /** create a new [Constraints] derived from the provided properties */
        operator fun invoke(
            directives: List<Directive>,
            possibleTypes: MaskedSet<GraphQLObjectType>?
        ): Constraints {
            val cds = directives.mapNotNull(ConditionalDirective::fromDirective)
            return if (cds.isEmpty() && possibleTypes == null) {
                Unconstrained
            } else {
                Impl(emptyList(), possibleTypes).withDirectives(directives)
            }
        }
    }
}
