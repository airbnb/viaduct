package viaduct.java.runtime.bridge

import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSets
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.checkDisjoint
import viaduct.engine.api.select.SelectionsParser
import viaduct.graphql.utils.collectVariableReferences
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.Variable

/**
 * Factory for creating [RequiredSelectionSets] from Java [Resolver] annotations.
 *
 * This is the Java equivalent of the Kotlin [viaduct.tenant.runtime.bootstrap.RequiredSelectionSetFactory].
 * It parses the [Resolver.objectValueFragment] and [Resolver.queryValueFragment] properties
 * and converts [Variable] annotations to [SelectionSetVariable] instances.
 *
 * Note: This factory does not support VariablesProvider (nested class variable providers).
 * It covers the simpler path using @Variable annotations, which handles most use cases.
 */
class JavaRequiredSelectionSetFactory {
    /**
     * Create a [RequiredSelectionSets] from the provided [Resolver] annotation.
     *
     * @param schema The Viaduct schema containing type definitions
     * @param annotation The @Resolver annotation from the resolver class
     * @param resolverForType The GraphQL type name this resolver is for (e.g., "Person")
     * @param resolverClassName The fully qualified class name of the resolver (for attribution)
     * @return A [RequiredSelectionSets] containing the parsed object and query selections
     */
    fun mkRequiredSelectionSets(
        schema: ViaductSchema,
        annotation: Resolver,
        resolverForType: String,
        resolverClassName: String,
    ): RequiredSelectionSets {
        val objectValueFragment = annotation.objectValueFragment
        val queryValueFragment = annotation.queryValueFragment

        // Parse selections using SelectionsParser (same as Kotlin)
        val objectSelections = if (objectValueFragment.isNotBlank()) {
            SelectionsParser.parse(resolverForType, objectValueFragment)
        } else {
            null
        }

        val querySelections = if (queryValueFragment.isNotBlank()) {
            SelectionsParser.parse(schema.schema.queryType.name, queryValueFragment)
        } else {
            null
        }

        if (objectSelections == null && querySelections == null) {
            return RequiredSelectionSets.empty()
        }

        // Convert @Variable annotations to SelectionSetVariable list
        val variables = annotation.variables.map { v -> v.toSelectionSetVariable() }

        // Validate that all declared variables are used
        val variableConsumers = buildSet<String> {
            objectSelections?.selections?.collectVariableReferences()?.let(::addAll)
            querySelections?.selections?.collectVariableReferences()?.let(::addAll)
        }
        val variableProducers = variables.map { it.name }.toSet()
        val unusedVariables = variableProducers - variableConsumers
        require(unusedVariables.isEmpty()) {
            "Cannot build RequiredSelectionSets: found declarations for unused variables: ${unusedVariables.joinToString(", ")}"
        }

        // Create VariablesResolver list (same pattern as Kotlin)
        val attribution = ExecutionAttribution.fromResolver(resolverClassName)
        val variableResolvers = mkFromAnnotationVariablesResolvers(
            objectSelections,
            querySelections,
            variables,
            attribution
        )

        // Build RequiredSelectionSets (same pattern as Kotlin)
        return RequiredSelectionSets(
            objectSelections = objectSelections?.let {
                RequiredSelectionSet(
                    it,
                    variableResolvers,
                    forChecker = false,
                    attribution
                )
            },
            querySelections = querySelections?.let {
                RequiredSelectionSet(
                    it,
                    variableResolvers,
                    forChecker = false,
                    attribution
                )
            }
        )
    }

    private fun mkFromAnnotationVariablesResolvers(
        objectSelections: ParsedSelections?,
        querySelections: ParsedSelections?,
        variables: List<SelectionSetVariable>,
        attribution: ExecutionAttribution?
    ): List<VariablesResolver> =
        VariablesResolver.fromSelectionSetVariables(
            objectSelections,
            querySelections,
            variables,
            forChecker = false,
            attribution
        ).also { it.checkDisjoint() }
            .map { it.validated() }
}

/**
 * Convert a Java [Variable] annotation to a [SelectionSetVariable].
 *
 * Exactly one of fromArgument, fromObjectField, or fromQueryField must be set.
 */
private fun Variable.toSelectionSetVariable(): SelectionSetVariable {
    val objectFieldIsSet = fromObjectField.isNotEmpty()
    val queryFieldIsSet = fromQueryField.isNotEmpty()
    val argIsSet = fromArgument.isNotEmpty()

    val setCount = listOf(objectFieldIsSet, queryFieldIsSet, argIsSet).count { it }

    check(setCount == 1) {
        "Variable named `$name` must set exactly one of `fromObjectField`, `fromQueryField`, or `fromArgument`. " +
            "It set fromObjectField=$fromObjectField, fromQueryField=$fromQueryField, fromArgument=$fromArgument"
    }

    return when {
        objectFieldIsSet -> FromObjectFieldVariable(name, fromObjectField)
        queryFieldIsSet -> FromQueryFieldVariable(name, fromQueryField)
        argIsSet -> FromArgumentVariable(name, fromArgument)
        else -> error("Unreachable: exactly one field should be set")
    }
}
