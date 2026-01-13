package viaduct.graphql.utils

import graphql.language.FragmentDefinition

/**
 * Utilities for parsing GraphQL selection sets and fragments that are shared by both build-time and
 * runtime logic.
 */
object SelectionsParserUtils {
    /** Fragment name used for entry point fragments in selections strings */
    const val EntryPointFragmentName: String = "Main"

    private val fragmentRegex = Regex("^\\s*fragment\\s+", RegexOption.MULTILINE)

    /**
     * Checks if a selections string is in shorthand form (field set) or longhand form (full fragment definition).
     * Shorthand form: "id name email"
     * Longhand form: "fragment Main on User { id name email }"
     *
     * Note: MULTILINE is needed so comments at the start of the string don't prevent matching
     * "fragment" on subsequent lines.
     */
    fun isShorthandForm(s: String): Boolean = !s.contains(fragmentRegex)

    /**
     * Wraps a shorthand selections string in a full fragment definition.
     * Converts "id name email" to "fragment Main on User { id name email }"
     */
    fun wrapShorthandAsFragment(
        selections: String,
        typeName: String
    ): String =
        """
        fragment $EntryPointFragmentName on $typeName {
            $selections
        }
        """.trimIndent()

    /**
     * Finds the entry point fragment from a list of fragment definitions.
     * - If there's exactly one fragment, it's used as the entry point
     * - If there are multiple fragments, the one named [EntryPointFragmentName] is used
     */
    fun findEntryPointFragment(fragments: List<FragmentDefinition>): FragmentDefinition {
        val entry =
            if (fragments.size == 1) {
                fragments.first()
            } else {
                fragments.find { it.name == EntryPointFragmentName }
            }
        requireNotNull(entry) {
            "selections must contain only 1 fragment or have 1 fragment definition named $EntryPointFragmentName"
        }
        return entry
    }
}
