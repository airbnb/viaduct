package viaduct.graphql.utils

import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.SelectionSet
import graphql.language.TypeName

/**
 * Utilities for parsing GraphQL selection sets and fragments that are shared by both build-time and
 * runtime logic.
 */
object SelectionsParserUtils {
    /** Fragment name used for entry point fragments in selections strings */
    const val EntryPointFragmentName: String = "Main"

    private val fragmentRegex = Regex("^\\s*fragment\\s+", RegexOption.MULTILINE)

    private val operationRegex =
        Regex("\\A(?:\\s*#[^\\n]*\\n)*\\s*(?:\\{|query\\b|mutation\\b|subscription\\b)")

    enum class SelectionsForm {
        SHORTHAND,
        FRAGMENTS,
        OPERATION,
    }

    /** Operations are detected first, since an operation document may itself contain fragments. */
    fun classify(s: String): SelectionsForm =
        when {
            operationRegex.containsMatchIn(s) -> SelectionsForm.OPERATION
            s.contains(fragmentRegex) -> SelectionsForm.FRAGMENTS
            else -> SelectionsForm.SHORTHAND
        }

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
     * Normalizes any `@Selections` string into a `fragment Main on <typeName>` document. An
     * operation's variable definitions are dropped, since variables bind by value at execution time.
     *
     * @throws IllegalArgumentException if an operation document does not contain exactly one operation.
     */
    fun normalizeToFragmentDocument(
        selections: String,
        typeName: String,
        parse: (String) -> Document,
    ): Document =
        when (classify(selections)) {
            SelectionsForm.SHORTHAND -> parse(wrapShorthandAsFragment(selections, typeName))
            SelectionsForm.FRAGMENTS -> parse(selections)
            SelectionsForm.OPERATION -> operationDocumentAsFragmentDocument(parse(selections), typeName)
        }

    /** @throws IllegalArgumentException if [document] does not contain exactly one operation. */
    private fun operationDocumentAsFragmentDocument(
        document: Document,
        typeName: String,
    ): Document {
        val operations = document.getDefinitionsOfType(OperationDefinition::class.java)
        require(operations.size == 1) {
            "a @GraphQLOperation document must contain exactly one operation, found ${operations.size}"
        }
        val entryFragment = FragmentDefinition.newFragmentDefinition()
            .name(EntryPointFragmentName)
            .typeCondition(TypeName(typeName))
            .selectionSet(operations.single().selectionSet)
            .build()
        val builder = Document.newDocument().definition(entryFragment)
        document.getDefinitionsOfType(FragmentDefinition::class.java).forEach(builder::definition)
        return builder.build()
    }

    /**
     * Appends the [knownFragments] reachable from [document]'s spreads so every spread resolves
     * locally. A fragment already defined in [document] shadows a same-named [knownFragments] entry.
     */
    fun inlineReachableFragments(
        document: Document,
        knownFragments: Map<String, FragmentDefinition>,
    ): Document {
        if (knownFragments.isEmpty()) return document

        val localFragments = document.getDefinitionsOfType(FragmentDefinition::class.java)
        val alreadyDefined = localFragments.mapTo(mutableSetOf()) { it.name }
        val reachable = mutableListOf<FragmentDefinition>()
        val queue = ArrayDeque<SelectionSet>()
        localFragments.forEach { queue.add(it.selectionSet) }

        while (queue.isNotEmpty()) {
            collectSpreadNames(queue.removeFirst()) { name ->
                if (name !in alreadyDefined) {
                    val def = knownFragments[name] ?: return@collectSpreadNames
                    alreadyDefined.add(name)
                    reachable.add(def)
                    queue.add(def.selectionSet)
                }
            }
        }
        if (reachable.isEmpty()) return document

        val builder = Document.newDocument()
        document.definitions.forEach(builder::definition)
        reachable.forEach(builder::definition)
        return builder.build()
    }

    private fun collectSpreadNames(
        selectionSet: SelectionSet,
        onSpread: (String) -> Unit,
    ) {
        selectionSet.selections.forEach { selection ->
            when (selection) {
                is FragmentSpread -> onSpread(selection.name)
                is Field -> selection.selectionSet?.let { collectSpreadNames(it, onSpread) }
                is InlineFragment -> collectSpreadNames(selection.selectionSet, onSpread)
            }
        }
    }

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
