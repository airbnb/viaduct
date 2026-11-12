package viaduct.engine.runtime2.model.registry

import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.SelectionForest

/**
 * A raw external node lookup accepted by registry construction.
 *
 * The returned object is partial and need not repeat the input ID. Node lowering retains the
 * authoritative ID supplied by the node-valued producer, matching production's node-reference
 * behavior. A node lookup may instead return a symbolic root-field reference; Resolution resolves
 * that instruction at the synthetic payload field just as it does for an ordinary field resolver.
 *
 * This wrapper is not part of the canonical resolver algebra. Registry construction consumes these
 * functions and exposes a field-only [ResolverRegistry].
 */
class NodeResolverFunction internal constructor(
    internal val mode: Mode,
    private val executionSelection: ((ResolutionExecutionContext) -> NodeResolverFunction)? = null,
    private val function: suspend (
        String,
        SelectionForest,
        ResolutionExecutionContext,
    ) -> ResolverOutputData?,
) {
    internal fun forExecution(context: ResolutionExecutionContext): NodeResolverFunction {
        val selected = executionSelection?.invoke(context) ?: return this
        require(selected.executionSelection == null) { "Execution selection must return a terminal node resolver" }
        return selected
    }

    internal enum class Mode {
        NONSELECTIVE,
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
): NodeResolverFunction = NodeResolverFunction(NodeResolverFunction.Mode.SELECTIVE, function = function)

/** Airbnb-only hook: preserve the selected lookup's projection and ID-only loading contract. */
@Deprecated("Airbnb use only")
fun selectingNodeResolverOf(
    bootstrap: NodeResolverFunction,
    select: (ResolutionExecutionContext) -> NodeResolverFunction,
): NodeResolverFunction = NodeResolverFunction(bootstrap.mode, select) { id, demand, context -> bootstrap(id, demand, context) }
