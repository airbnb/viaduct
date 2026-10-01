package model.registry

import model.ResolverOutputData
import model.SelectionForest

/**
 * A raw external node lookup accepted by registry construction.
 *
 * The returned object is partial and need not repeat the input ID. Node lowering retains the
 * authoritative ID supplied by the node-valued producer, matching production's node-reference
 * behavior. A node lookup may instead return a symbolic root-field reference; Resolver26 resolves
 * that instruction at the synthetic payload field just as it does for an ordinary field resolver.
 *
 * This wrapper is not part of the canonical resolver algebra. [resolverRegistryOf] consumes these
 * functions and exposes a field-only [ResolverRegistry].
 */
class NodeResolverFunction internal constructor(
    internal val mode: Mode,
    private val function: suspend (
        String,
        SelectionForest,
        ResolutionExecutionContext,
    ) -> ResolverOutputData?,
) {
    internal enum class Mode {
        NONSELECTIVE,
        SELECTION_AWARE_NONSELECTIVE,
        SELECTIVE,
    }

    internal suspend operator fun invoke(
        id: String,
        selections: SelectionForest,
        executionContext: ResolutionExecutionContext,
    ): ResolverOutputData? = function(id, selections, executionContext)
}

/** Marks a raw external node lookup for registry construction. */
fun nodeResolverOf(function: suspend (String) -> ResolverOutputData?): NodeResolverFunction = NodeResolverFunction(NodeResolverFunction.Mode.NONSELECTIVE) { id, _, _ -> function(id) }

fun nodeResolverOf(function: suspend (String, ResolutionExecutionContext) -> ResolverOutputData?): NodeResolverFunction =
    NodeResolverFunction(NodeResolverFunction.Mode.NONSELECTIVE) { id, _, executionContext ->
        function(id, executionContext)
    }

/** Marks a stable raw node lookup that receives demand before model-owned output projection. */
fun selectionAwareNodeResolverOf(function: suspend (String, SelectionForest) -> ResolverOutputData?): NodeResolverFunction =
    NodeResolverFunction(NodeResolverFunction.Mode.SELECTION_AWARE_NONSELECTIVE) { id, selections, _ ->
        function(id, selections)
    }

fun selectionAwareNodeResolverOf(
    function: suspend (
        String,
        SelectionForest,
        ResolutionExecutionContext,
    ) -> ResolverOutputData?,
): NodeResolverFunction = NodeResolverFunction(NodeResolverFunction.Mode.SELECTION_AWARE_NONSELECTIVE, function)

/** Marks a selection-sensitive raw external node lookup for registry construction. */
fun selectiveNodeResolverOf(function: suspend (String, SelectionForest) -> ResolverOutputData?): NodeResolverFunction =
    NodeResolverFunction(NodeResolverFunction.Mode.SELECTIVE) { id, selections, _ ->
        function(id, selections)
    }

fun selectiveNodeResolverOf(
    function: suspend (
        String,
        SelectionForest,
        ResolutionExecutionContext,
    ) -> ResolverOutputData?,
): NodeResolverFunction = NodeResolverFunction(NodeResolverFunction.Mode.SELECTIVE, function)
