package viaduct.engine.runtime.tenantloading

import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.validation.Validator

/**
 * Validates that the required selection sets on a resolver are on the correct type,
 * and are valid for that type.  Specifically, objectSelections must be on the
 * field-coordinate's type and querySelections on the query type.
 */
class ResolverSelectionSetsAreProperlyTyped(
    private val schema: ViaductSchema,
) : Validator<FieldResolverExecutorValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: FieldResolverExecutorValidationCtx) =
        ctx.run {
            val objRSSName = executor.objectSelectionSet?.selections?.typeName
            val objectName = coord.first

            val qryRSSName = executor.querySelectionSet?.selections?.typeName
            val queryName = schema.schema.getQueryType().name

            var msg: String? = null
            if (objRSSName != null && objectName != objRSSName) {
                msg = "Object selection type ($objRSSName) does not match coordinate type ($objectName)"
            }
            if (qryRSSName != null && queryName != qryRSSName) {
                msg = msg?.let { msg + " and " } ?: ""
                msg += "Query selection type ($qryRSSName) does not match query type ($queryName)"
            }
            if (msg != null) throw BadResolverSelectionSetTypeException(msg)
        }
}

private class BadResolverSelectionSetTypeException(msg: String) : Exception(msg)
