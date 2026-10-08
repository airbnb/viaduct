package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.language.Directive

/** An active defer identified by its directive and resolved label. */
data class Defer(val label: String?, private val directive: Directive)

/** A defer occurrence and its enclosing defer context. */
data class DeferUsage(val defer: Defer, val parent: DeferUsage?)

/**
 * DeferDeliveryGroup is an execution-time representation of a `@defer`, representing
 * the fulfillment of a `@defer` for a specific position in a response tree.
 *
 * It is defined by the spec in 6.5.1 GetNewDeferMap as the informal term
 * "deferred fragment".
 *
 * For example, consider this query:
 * ```graphql
 *  {
 *    list {
 *      ... @defer(label: "A") { a }
 *    }
 *  }
 * ```
 *
 * A value of `DeferDeliveryGroup` for this query might be
 * `DeferDeliveryGroup(["list", 1], "A", null)`, which describes the deferred result for
 * the second object returned by `list`.
 *
 * This can be contrasted to a [DeferUsage] value like `DeferUsage("A", null)`, which
 * describes the unexecuted textual occurrence of a `@defer`.
 */
class DeferDeliveryGroup(val path: ResultPath, val label: String?, val parent: DeferDeliveryGroup?)

/**
 * A binding of query-level [DeferUsage] to its [DeferDeliveryGroup] execution representation.
 *
 * Defined in 6.5.1 Mapping @defer Directives to Delivery Groups
 */
typealias DeferMap = Map<DeferUsage, DeferDeliveryGroup>
