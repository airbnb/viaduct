package viaduct.engine.runtime.tenantloading

import graphql.Directives
import graphql.language.Directive
import graphql.language.FragmentSpread
import graphql.language.Node
import graphql.language.VariableReference
import viaduct.engine.runtime.validation.Validator
import viaduct.graphql.utils.ParsedSelections

/**
 * Rejects required selection sets whose `@skip` or `@include` conditions reference a from-field variable.
 */
class FromFieldVariablesAreNotConditions : Validator<RequiredSelectionsValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: RequiredSelectionsValidationCtx) {
        val allSets = if (ctx.fieldName != null) {
            ctx.requiredSelectionSetRegistry.getRequiredSelectionSetsForField(ctx.typeName, ctx.fieldName)
        } else {
            ctx.requiredSelectionSetRegistry.getRequiredSelectionSetsForType(ctx.typeName)
        }

        allSets.forEach { rss ->
            val fromFieldVariableNames = rss.variablesResolvers
                .mapNotNull { it.asFromFieldVariablesResolver() }
                .mapTo(mutableSetOf()) { it.name }
            if (fromFieldVariableNames.isEmpty()) return@forEach

            rss.selections.conditionVariableNames()
                .firstOrNull { it in fromFieldVariableNames }
                ?.let { name ->
                    throw InvalidVariableException(
                        ctx.typeName to ctx.fieldName,
                        name,
                        "From-field variables cannot be used in @skip or @include directives " +
                            "(found in ${if (rss.forChecker) "checker" else "resolver"} required selections). " +
                            "Select the value instead and fetch the conditional data with ctx.query()."
                    )
                }
        }
    }

    private fun ParsedSelections.conditionVariableNames(): Set<String> {
        val names = mutableSetOf<String>()
        val visitedFragments = mutableSetOf<String>()

        fun visit(node: Node<*>) {
            when (node) {
                is Directive -> if (node.name == Directives.SkipDirective.name || node.name == Directives.IncludeDirective.name) {
                    (node.getArgument("if")?.value as? VariableReference)?.let { names += it.name }
                }
                is FragmentSpread -> if (visitedFragments.add(node.name)) {
                    fragmentMap[node.name]?.let(::visit)
                }
            }
            node.children.forEach(::visit)
        }

        visit(selections)
        return names
    }
}
