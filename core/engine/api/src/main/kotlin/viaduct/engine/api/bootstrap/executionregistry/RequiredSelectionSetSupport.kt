package viaduct.engine.api.bootstrap.executionregistry

import viaduct.bootstrap.ProviderVariablesAPIData
import viaduct.bootstrap.SelectionsBlockConfig
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSets
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.checkDisjoint
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.collectVariableReferences

object RequiredSelectionSetSupport {
    data class AssemblyInput(
        val objectSelections: ParsedSelections?,
        val querySelections: ParsedSelections?,
        val variables: List<SelectionSetVariable>,
        val providerResolvers: List<VariablesResolver>,
        val attribution: ExecutionAttribution?,
    )

    fun buildRequiredSelectionSets(input: AssemblyInput): RequiredSelectionSets {
        val (objectSelections, querySelections, variables, providerResolvers, attribution) = input
        if (objectSelections == null && querySelections == null) {
            return RequiredSelectionSets.empty()
        }

        val variableConsumers = buildSet {
            objectSelections?.let { addAll(it.collectVariableReferences()) }
            querySelections?.let { addAll(it.collectVariableReferences()) }
        }
        val variableProducers = buildSet {
            variables.forEach { add(it.name) }
            providerResolvers.forEach { addAll(it.variableNames) }
        }
        val unusedVariables = variableProducers - variableConsumers
        require(unusedVariables.isEmpty()) {
            "Cannot build required selection sets: found declarations for unused variables: ${unusedVariables.joinToString(", ")}"
        }

        val variableResolvers = (
            providerResolvers + VariablesResolver.fromSelectionSetVariables(
                objectSelections,
                querySelections,
                variables,
                forChecker = false,
                attribution,
            )
        ).also { it.checkDisjoint() }.map { it.validated() }

        fun build(selections: ParsedSelections): RequiredSelectionSet = RequiredSelectionSet(selections, variableResolvers, forChecker = false, attribution)

        return RequiredSelectionSets(
            objectSelections = objectSelections?.let(::build),
            querySelections = querySelections?.let(::build),
        )
    }

    /**
     * Decode a single registry [ProviderVariablesAPIData] entry into a [SelectionSetVariable] for the
     * named variable.
     */
    fun toSelectionSetVariable(
        data: ProviderVariablesAPIData,
        name: String,
    ): SelectionSetVariable =
        when (data.type) {
            "fromArgument" -> FromArgumentVariable(name, data.path)
            "fromObjectField" -> FromObjectFieldVariable(name, data.path)
            "fromQueryField" -> FromQueryFieldVariable(name, data.path)
            else -> error("Unknown variable provider type '${data.type}' for variable '$name'")
        }

    /**
     * Flatten the variable-provider declarations from a resolver's object- and query-level selection
     * blocks into the list of [SelectionSetVariable]s they declare.
     */
    fun buildSelectionSetVariables(
        objectSelections: SelectionsBlockConfig?,
        querySelections: SelectionsBlockConfig?,
    ): List<SelectionSetVariable> =
        (
            (objectSelections?.variablesProviders ?: emptyList()) +
                (querySelections?.variablesProviders ?: emptyList())
        ).flatMap { providerEntry ->
            providerEntry.providedVariables.keys.map { varName ->
                toSelectionSetVariable(providerEntry.providerVariablesAPIData, varName)
            }
        }

    /**
     * Parse `@Variables`-style entries of the form `"name: Type"` into a map of variable name to type
     * expression. Blank entries are ignored; malformed entries throw [IllegalArgumentException].
     *
     * Callers that only need the declared names can use the returned map's `keys`.
     */
    fun parseVariableTypeEntries(entries: Iterable<String>): Map<String, String> =
        entries
            .filter { it.isNotBlank() }
            .associate { entry ->
                val parts = entry.trim().split(":")
                require(parts.size == 2) {
                    "Invalid @Variables entry '${entry.trim()}' — expected format 'name: Type'"
                }
                val name = parts[0].trim()
                require(name.isNotEmpty()) {
                    "Invalid @Variables entry '${entry.trim()}' — variable name is empty"
                }
                val type = parts[1].trim()
                require(type.isNotEmpty()) {
                    "Invalid @Variables entry '${entry.trim()}' — variable type is empty"
                }
                name to type
            }
}
