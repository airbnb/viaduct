package viaduct.engine.runtime.tenantloading

import graphql.language.Field
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldsContainer
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.runtime.validation.Validator
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.isParentField

class CheckerSelectionSetsDoNotSelectParentFields(
    private val schema: EngineSchema,
) : Validator<CheckerExecutorValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: CheckerExecutorValidationCtx) {
        val violations = ctx.executor.requiredSelectionSets.mapNotNull { (key, rss) ->
            rss?.withVariablesResolverSelectionSets()
                ?.flatMapTo(sortedSetOf()) { it.selections.parentFieldCoordinates() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { "$key: $it" }
        }
        if (violations.isNotEmpty()) {
            throw ParentFieldInCheckerSelectionSetException(
                "Checker required selections for ${ctx.typeName} select @parent fields (${violations.joinToString("; ")}). " +
                    "Access checkers may not read @parent fields."
            )
        }
    }

    private fun RequiredSelectionSet.withVariablesResolverSelectionSets(): List<RequiredSelectionSet> =
        listOf(this) + variablesResolvers.mapNotNull { it.requiredSelectionSet }.flatMap { it.withVariablesResolverSelectionSets() }

    private fun ParsedSelections.parentFieldCoordinates(): Set<String> {
        val coordinates = mutableSetOf<String>()
        val visitedFragments = mutableSetOf<String>()

        fun walk(
            selectionSet: SelectionSet?,
            parentType: GraphQLCompositeType,
        ) {
            selectionSet?.selections?.forEach { selection ->
                when (selection) {
                    is Field -> {
                        val fieldDefinition = (parentType as? GraphQLFieldsContainer)?.getFieldDefinition(selection.name) ?: return@forEach
                        if (fieldDefinition.isParentField()) coordinates += "${parentType.name}.${selection.name}"
                        (GraphQLTypeUtil.unwrapAll(fieldDefinition.type) as? GraphQLCompositeType)?.let { walk(selection.selectionSet, it) }
                    }
                    is InlineFragment -> walk(selection.selectionSet, selection.typeCondition?.let { compositeType(it.name) } ?: parentType)
                    is FragmentSpread -> if (visitedFragments.add(selection.name)) {
                        val fragment = fragmentMap[selection.name] ?: return@forEach
                        compositeType(fragment.typeCondition.name)?.let { walk(fragment.selectionSet, it) }
                    }
                }
            }
        }

        compositeType(typeName)?.let { walk(selections, it) }
        return coordinates
    }

    private fun compositeType(name: String): GraphQLCompositeType? = schema.schema.getType(name) as? GraphQLCompositeType
}

private class ParentFieldInCheckerSelectionSetException(msg: String) : Exception(msg)
