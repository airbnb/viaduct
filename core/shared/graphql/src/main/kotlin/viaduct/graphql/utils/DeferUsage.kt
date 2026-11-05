package viaduct.graphql.utils

import graphql.Directives
import graphql.language.Directive
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.Node
import graphql.language.SourceLocation

/** Describes an @defer directive found by [findDeferUsages] and where it appeared. */
data class DeferUsage(val fragmentName: String?, val sourceLocation: SourceLocation?) {
    override fun toString(): String =
        buildString {
            append("@defer")
            fragmentName?.let { append(" in fragment '$it'") }
            sourceLocation?.let { append(" at ${it.line}:${it.column}") }
        }
}

/** Returns @defer usages in these selections and the fragments they reference. */
fun ParsedSelections.findDeferUsages(): List<DeferUsage> {
    val entryFragment = fragmentMap.values.firstOrNull { it.selectionSet === selections }
    return findDeferUsages(entryFragment ?: selections, fragmentMap)
}

/**
 * Returns @defer usages in [root], following fragment spreads using [fragmentMap].
 * Checks each referenced fragment once to avoid repeated work from reused fragments or fragment cycles.
 * Also reports @defer(if: false) and @defer in selections with @skip(if: true).
 */
fun findDeferUsages(
    root: Node<*>,
    fragmentMap: Map<String, FragmentDefinition>
): List<DeferUsage> {
    val usages = mutableListOf<DeferUsage>()
    val visitedFragments = mutableSetOf<String>()

    fun visit(
        node: Node<*>,
        enclosingFragment: String?
    ) {
        val fragmentName = if (node is FragmentDefinition) {
            if (!visitedFragments.add(node.name)) return
            node.name
        } else {
            enclosingFragment
        }

        when (node) {
            is Directive -> if (node.name == Directives.DeferDirective.name) {
                usages += DeferUsage(fragmentName, node.sourceLocation)
            }
            is FragmentSpread -> fragmentMap[node.name]?.let { visit(it, fragmentName) }
        }
        node.children.forEach { visit(it, fragmentName) }
    }

    visit(root, null)
    return usages
}
