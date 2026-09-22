package viaduct.engine.runtime.execution

import graphql.language.Directive

/** An active defer identified by its directive and resolved label. */
data class Defer(val label: String?, private val directive: Directive)

/** A defer occurrence and its enclosing defer context. */
data class DeferUsage(val defer: Defer, val parent: DeferUsage?)
