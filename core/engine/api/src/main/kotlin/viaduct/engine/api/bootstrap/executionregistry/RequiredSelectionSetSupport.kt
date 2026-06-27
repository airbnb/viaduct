package viaduct.engine.api.bootstrap.executionregistry

import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.SelectionSetVariable

/**
 * Language-neutral helpers for decoding the build-time execution-registry variable model into the
 * engine's [SelectionSetVariable] declarations.
 *
 * Both tenant runtimes — the Kotlin
 * [viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory] and the Java
 * [viaduct.java.runtime.bridge.RequiredSelectionSetFactory] — read the same registry JSON
 * ([ProviderVariablesAPIData] / [SelectionsBlockConfig]) and produced byte-identical copies of this
 * decoding logic. The behavior is purely a function of the registry model (no `suspend`, no
 * GRT/reflection differences), so it is single-sourced here rather than per language.
 */
object RequiredSelectionSetSupport {
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
