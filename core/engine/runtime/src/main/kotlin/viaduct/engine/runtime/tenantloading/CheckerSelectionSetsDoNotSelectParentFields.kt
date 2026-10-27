package viaduct.engine.runtime.tenantloading

import graphql.analysis.QueryTraversalOptions
import graphql.analysis.QueryVisitorFieldEnvironment
import graphql.analysis.QueryVisitorStub
import graphql.schema.GraphQLCompositeType
import viaduct.engine.api.EngineSchema
import viaduct.engine.runtime.validation.Validator
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.ViaductQueryTraverser
import viaduct.graphql.utils.isParentField

class CheckerSelectionSetsDoNotSelectParentFields(
    private val schema: EngineSchema,
) : Validator<CheckerExecutorValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: CheckerExecutorValidationCtx) {
        val parentFieldCoordinates = ctx.executor.requiredSelectionSets.values
            .filterNotNull()
            .flatMapTo(sortedSetOf()) { it.selections.parentFieldCoordinates() }
        if (parentFieldCoordinates.isNotEmpty()) {
            throw ParentFieldInCheckerSelectionSetException(
                "Checker required selections for ${ctx.typeName} select @parent fields $parentFieldCoordinates. " +
                    "Access checkers may not read @parent fields."
            )
        }
    }

    private fun ParsedSelections.parentFieldCoordinates(): Set<String> {
        val coordinates = mutableSetOf<String>()
        ViaductQueryTraverser
            .newQueryTraverser()
            .schema(schema.schema)
            .root(selections)
            .rootParentType(schema.schema.getType(typeName) as GraphQLCompositeType)
            .fragmentsByName(fragmentMap)
            .options(QueryTraversalOptions.defaultOptions().coerceFieldArguments(false))
            .build()
            .visitPreOrder(
                object : QueryVisitorStub() {
                    override fun visitField(env: QueryVisitorFieldEnvironment) {
                        if (env.fieldDefinition.isParentField()) {
                            coordinates += "${env.fieldsContainer.name}.${env.field.name}"
                        }
                    }
                }
            )
        return coordinates
    }
}

private class ParentFieldInCheckerSelectionSetException(msg: String) : Exception(msg)
