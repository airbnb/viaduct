package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet

/**
 * Shared resolution logic for [ObjectRootFieldReference] and [AbstractRootFieldReference].
 */
internal object RootFieldReferenceHelpers {
    /**
     * Resolves a root field reference by constructing a selection set from a field path and args,
     * executing it via [EngineExecutionContext.resolveSelectionSet], and extracting the root
     * field result.
     */
    suspend fun resolveRootFieldReference(
        rootFieldPath: List<String>,
        args: Map<String, Any?>,
        selections: EngineSelectionSet,
        context: EngineExecutionContext,
    ): EngineObjectData {
        require(rootFieldPath.isNotEmpty()) { "rootFieldPath must not be empty" }

        val leafSelections = selections.printAsFieldSet()
        val selectionString = buildPathSelectionString(rootFieldPath, args, leafSelections)
        // Prefix root field arg variable names to avoid collisions with operation or
        // child-plan variables that may appear in the leaf selections (e.g., field args
        // like `reviews(limit: $n)` or directives like `@include(if: $flag)`).
        val prefixedArgs = args.mapKeys { (key, _) -> "__rfr_$key" }
        // Merge operation-level variables (needed because printAsFieldSet() may serialize
        // variable references from the original query) with the prefixed root field args.
        val variables = context.fieldScope.variables + prefixedArgs

        // TODO: this should be intersected with the root field's output selection set,
        // to avoid redundantly executing child fields with their own resolvers
        val engineSS = context.engineSelectionSetFactory.engineSelectionSet(
            "Query",
            selectionString,
            variables,
        )
        val queryResult = context.resolveSelectionSet(engineSS)
        return extractNestedResult(queryResult, rootFieldPath)
    }

    /**
     * Builds a nested selection string from a field path, arguments, and leaf selections.
     * Arguments use prefixed variable references (`$__rfr_<name>`) to avoid collisions
     * with variables in the leaf selections.
     *
     * e.g., path=["_factories", "ugcText", "create"],
     *       args={"sourceText": "hello"},
     *       leafSelections="name price"
     * → "_factories { ugcText { create(sourceText: $__rfr_sourceText) { name price } } }"
     */
    internal fun buildPathSelectionString(
        path: List<String>,
        args: Map<String, Any?>,
        leafSelections: String,
    ): String {
        val leafField = path.last()
        val argsString = if (args.isEmpty()) {
            ""
        } else {
            args.keys.joinToString(", ", prefix = "(", postfix = ")") { key -> "$key: \$__rfr_$key" }
        }
        var result = "$leafField$argsString { $leafSelections }"
        for (field in path.dropLast(1).reversed()) {
            result = "$field { $result }"
        }
        return result
    }

    /**
     * Walks a field path on an [EngineObjectData] to extract the nested result.
     */
    internal suspend fun extractNestedResult(
        data: EngineObjectData,
        path: List<String>,
    ): EngineObjectData {
        var current: EngineObjectData = data
        for ((index, field) in path.withIndex()) {
            val fetched = current.fetch(field)
            check(fetched is EngineObjectData) {
                "Expected EngineObjectData at path segment '$field' (index $index in ${path.joinToString(".")}), " +
                    "got ${fetched?.let { it::class.simpleName } ?: "null"}"
            }
            current = fetched
        }
        return current
    }
}
