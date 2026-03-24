package viaduct.engine.runtime.tenantloading

import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.validation.Validator

/**
* Validates that the checker required selection sets are on either:
* - the type of the field coordinate for a field checker
* - the type itself for a type checker
* - the root query type.
 */
class CheckerSelectionSetsAreProperlyTyped(
    private val schema: ViaductSchema,
) : Validator<CheckerExecutorValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: CheckerExecutorValidationCtx) =
        ctx.run {
            val rootQueryTypeName = schema.schema.queryType.name
            val mismatches = mutableSetOf<String>()

            for (rss in executor.requiredSelectionSets.values) {
                val rssName = rss?.selections?.typeName
                if (typeName != rssName && rssName != null && rssName != rootQueryTypeName) mismatches.add(rssName)
            }
            if (!mismatches.isEmpty()) {
                throw BadCheckerSelectionSetTypeException(
                    "Some selection types ($mismatches) do not match " +
                        "object type ($typeName) or root query type."
                )
            }
        }
}

private class BadCheckerSelectionSetTypeException(msg: String) : Exception(msg)
