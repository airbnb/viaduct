package viaduct.engine

import graphql.ExecutionInput
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.Selection

/**
 * Represents the introspection analysis result for a GraphQL operation.
 */
class InspectIntrospections private constructor(
    val operationDefinition: OperationDefinition,
    val hasIntrospection: Boolean,
    val hasNonIntrospection: Boolean
) {
    companion object {
        /**
         * If [document] supports an execution from [operationName], then returns
         * the introspective query status of [document]. Otherwise returns null.
         *
         * @param document The GraphQL document to analyze
         * @param operationName The name of [ExecutionInput.operationName] for the proposed execution
         * @return InspectIntrospections object with (hasIntrospection, hasNonIntrospection), or null if no legit operation
         */
        fun fromDocument(
            document: Document,
            operationName: String?
        ): InspectIntrospections? {
            val opDef: OperationDefinition = if (operationName == null) {
                document.getFirstDefinitionOfType(OperationDefinition::class.java).orElse(null)
            } else {
                document.getOperationDefinition(operationName).orElse(null)
            } ?: return null

            return try {
                val (hasIntrospection, hasNonIntrospection) = inspectIntrospection(
                    opDef.selectionSet.selections,
                    LazyFragMap(document)
                )
                InspectIntrospections(opDef, hasIntrospection, hasNonIntrospection)
            } catch (e: LazyFragMapNotFoundException) {
                null
            }
        }

        private fun inspectIntrospection(
            selections: List<Selection<*>>,
            fragmentsByName: LazyFragMap,
        ): Pair<Boolean, Boolean> {
            var hasIntrospection = false
            var hasNonIntrospection = false

            for (selection in selections) {
                when (selection) {
                    is Field -> {
                        if (selection.name.equals("__schema", true) || selection.name.equals("__type", true)) {
                            hasIntrospection = true
                        } else {
                            hasNonIntrospection = true
                        }
                    }
                    is InlineFragment -> {
                        val (fragIntrospection, fragNonIntrospection) = inspectIntrospection(
                            selection.selectionSet.selections,
                            fragmentsByName
                        )
                        hasIntrospection = hasIntrospection || fragIntrospection
                        hasNonIntrospection = hasNonIntrospection || fragNonIntrospection
                    }
                    is FragmentSpread -> {
                        val fragment = fragmentsByName.find(selection.name)
                        if (fragment != null) {
                            val (fragIntrospection, fragNonIntrospection) = inspectIntrospection(
                                fragment.selectionSet.selections,
                                fragmentsByName
                            )
                            hasIntrospection = hasIntrospection || fragIntrospection
                            hasNonIntrospection = hasNonIntrospection || fragNonIntrospection
                        }
                    }
                }

                // Early exit if both are found
                if (hasIntrospection && hasNonIntrospection) {
                    break
                }
            }

            return Pair(hasIntrospection, hasNonIntrospection)
        }
    }
}

private class LazyFragMapNotFoundException : NoSuchElementException()

private class LazyFragMap(val doc: Document) {
    private lateinit var fragsByName: MutableMap<String, FragmentDefinition>
    private val defIter = doc.definitions.iterator()

    fun find(fragName: String): FragmentDefinition? {
        if (!::fragsByName.isInitialized) {
            fragsByName = mutableMapOf<String, FragmentDefinition>()
        }

        // Check if we already have it cached
        fragsByName[fragName]?.let { return it }

        // Search for it in the remaining definitions
        while (defIter.hasNext()) {
            val def = defIter.next()
            if (def is FragmentDefinition) {
                fragsByName[def.name] = def
                if (def.name == fragName) {
                    return def
                }
            }
        }

        // Not found
        throw LazyFragMapNotFoundException()
    }
}
