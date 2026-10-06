package viaduct.engine.runtime2.model

/** A symbolic Boolean condition controlling whether one selection occurrence is included. */
sealed interface InclusionCondition {
    fun include(bindings: Map<Arguments.Variable, Boolean>): Boolean

    fun includeWith(binding: (Arguments.Variable) -> Boolean): Boolean

    suspend fun include(binding: suspend (Arguments.Variable) -> Boolean): Boolean

    fun and(other: InclusionCondition): InclusionCondition = conjunction(this, other)

    fun or(other: InclusionCondition): InclusionCondition = disjunction(listOf(this, other))

    fun usedVariables(): Set<Arguments.Variable>

    fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): InclusionCondition

    data object Always : InclusionCondition {
        override suspend fun include(binding: suspend (Arguments.Variable) -> Boolean) = true

        override fun include(bindings: Map<Arguments.Variable, Boolean>) = true

        override fun includeWith(binding: (Arguments.Variable) -> Boolean) = true

        override fun usedVariables(): Set<Arguments.Variable> = emptySet()

        override fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): InclusionCondition = this
    }

    data object Never : InclusionCondition {
        override suspend fun include(binding: suspend (Arguments.Variable) -> Boolean) = false

        override fun include(bindings: Map<Arguments.Variable, Boolean>) = false

        override fun includeWith(binding: (Arguments.Variable) -> Boolean) = false

        override fun usedVariables(): Set<Arguments.Variable> = emptySet()

        override fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): InclusionCondition = this
    }

    data class Requires(
        val values: Map<Arguments.Variable, Boolean>,
    ) : InclusionCondition {
        override suspend fun include(binding: suspend (Arguments.Variable) -> Boolean): Boolean {
            values.forEach { (variable, required) ->
                if (binding(variable) != required) return false
            }
            return true
        }

        override fun include(bindings: Map<Arguments.Variable, Boolean>): Boolean = values.all { (variable, required) -> bindings.getValue(variable) == required }

        override fun includeWith(binding: (Arguments.Variable) -> Boolean): Boolean = values.all { (variable, required) -> binding(variable) == required }

        override fun usedVariables(): Set<Arguments.Variable> = values.keys

        override fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): InclusionCondition =
            values.entries.fold(Always as InclusionCondition) { condition, (variable, required) ->
                condition.and(requires(mapOf(transform(variable) to required)))
            }
    }

    companion object {
        fun requires(values: Map<Arguments.Variable, Boolean>): InclusionCondition = if (values.isEmpty()) Always else Requires(values.toMap())

        fun anyOf(conditions: Iterable<InclusionCondition>): InclusionCondition = disjunction(conditions.toList())
    }
}

private class AnyOf(
    val alternatives: List<InclusionCondition>,
) : InclusionCondition {
    override suspend fun include(binding: suspend (Arguments.Variable) -> Boolean): Boolean {
        alternatives.forEach { alternative ->
            if (alternative.include(binding)) return true
        }
        return false
    }

    override fun include(bindings: Map<Arguments.Variable, Boolean>): Boolean = alternatives.any { alternative -> alternative.include(bindings) }

    override fun includeWith(binding: (Arguments.Variable) -> Boolean): Boolean = alternatives.any { alternative -> alternative.includeWith(binding) }

    override fun usedVariables(): Set<Arguments.Variable> = alternatives.flatMapTo(linkedSetOf()) { it.usedVariables() }

    override fun mapVariables(transform: (Arguments.Variable) -> Arguments.Variable): InclusionCondition = disjunction(alternatives.map { it.mapVariables(transform) })
}

private fun conjunction(
    first: InclusionCondition,
    second: InclusionCondition,
): InclusionCondition =
    when {
        first === InclusionCondition.Never || second === InclusionCondition.Never ->
            InclusionCondition.Never
        first === InclusionCondition.Always -> second
        second === InclusionCondition.Always -> first
        first is AnyOf -> disjunction(first.alternatives.map { conjunction(it, second) })
        second is AnyOf -> disjunction(second.alternatives.map { conjunction(first, it) })
        first is InclusionCondition.Requires && second is InclusionCondition.Requires -> {
            val conflicts =
                first.values.any { (variable, value) ->
                    second.values[variable]?.let { it != value } == true
                }
            if (conflicts) {
                InclusionCondition.Never
            } else {
                InclusionCondition.requires(first.values + second.values)
            }
        }
        else -> error("Unexpected inclusion-condition conjunction")
    }

private fun disjunction(conditions: List<InclusionCondition>): InclusionCondition {
    val alternatives =
        conditions.flatMap { condition ->
            when (condition) {
                InclusionCondition.Never -> emptyList()
                is AnyOf -> condition.alternatives
                else -> listOf(condition)
            }
        }.distinct()
    if (alternatives.any { it === InclusionCondition.Always }) return InclusionCondition.Always
    return when (alternatives.size) {
        0 -> InclusionCondition.Never
        1 -> alternatives.single()
        else -> AnyOf(alternatives)
    }
}

internal fun InclusionCondition.alternatives(): List<InclusionCondition> = if (this is AnyOf) alternatives else listOf(this)

/** Returns the normalized, satisfiable conjunctions whose disjunction is this condition. */
fun InclusionCondition.satisfiableAlternatives(): List<InclusionCondition> =
    when (this) {
        InclusionCondition.Never -> emptyList()
        is AnyOf -> alternatives
        else -> listOf(this)
    }
