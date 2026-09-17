package viaduct.engine.runtime.execution

import graphql.language.Directive

/** Collections of the same AST directive share an identity across field occurrences. */
class Defer(val label: String?, private val directive: Directive? = null) {
    override fun equals(other: Any?): Boolean = this === other || (other is Defer && directive != null && directive === other.directive)

    override fun hashCode(): Int = System.identityHashCode(directive ?: this)
}

/** A defer occurrence and its enclosing defer context. */
data class DeferUsage(val defer: Defer, val parent: DeferUsage?)
