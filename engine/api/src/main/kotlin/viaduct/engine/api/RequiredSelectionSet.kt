package viaduct.engine.api

import graphql.language.AstPrinter
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.QueryPlanExecutionCondition.Companion.ALWAYS_EXECUTE
import viaduct.graphql.utils.collectVariableReferences

/**
 * Represents a set of selections that are required.
 *
 * @param forChecker True if this is a RSS for a checker or checker variable resolver
 * @param executionCondition Determines whether QueryPlans built from this RSS should execute at runtime (defaults to always execute).
 */
class RequiredSelectionSet(
    val selections: ParsedSelections,
    val variablesResolvers: List<VariablesResolver>,
    val forChecker: Boolean,
    val attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
    val executionCondition: QueryPlanExecutionCondition = ALWAYS_EXECUTE,
) {
    init {
        val refs = selections.selections.collectVariableReferences()
        val resolvers = variablesResolvers.flatMap { it.variableNames }
        val missing = refs - resolvers
        if (missing.isNotEmpty()) {
            throw UnboundVariablesException(selections, missing)
        }
    }
}

class UnboundVariablesException(selections: ParsedSelections, missing: Set<String>) : Exception() {
    override val message: String by lazy {
        val selectionsStr = AstPrinter.printAst(selections.selections)
        """
                |Selections contain unresolvable variable references: $missing
                |Selection set:
                |$selectionsStr
        """.trimMargin()
    }
}
