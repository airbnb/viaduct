package viaduct.engine.runtime.tenantloading

import viaduct.engine.runtime.validation.Validator
import viaduct.graphql.utils.findDeferUsages

/**
 * Validates that required selection sets for field resolvers and access checkers do not use @defer.
 * Checks referenced fragments as well and throws [IllegalArgumentException] for any @defer usage.
 */
class RequiredSelectionsDoNotUseDefer : Validator<RequiredSelectionsValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: RequiredSelectionsValidationCtx) {
        val requiredSelections = if (ctx.fieldName != null) {
            ctx.requiredSelectionSetRegistry.getRequiredSelectionSetsForField(ctx.typeName, ctx.fieldName)
        } else {
            ctx.requiredSelectionSetRegistry.getRequiredSelectionSetsForType(ctx.typeName)
        }
        val owner = if (ctx.fieldName != null) "${ctx.typeName}.${ctx.fieldName}" else ctx.typeName

        requiredSelections.forEach { rss ->
            val usages = rss.selections.findDeferUsages()
            require(usages.isEmpty()) {
                "@defer is not allowed in required selection sets. " +
                    "Required selections for $owner must resolve synchronously. Found: ${usages.joinToString()}"
            }
        }
    }
}
