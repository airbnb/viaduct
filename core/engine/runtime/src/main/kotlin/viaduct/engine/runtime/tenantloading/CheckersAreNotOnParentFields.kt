package viaduct.engine.runtime.tenantloading

import viaduct.engine.api.EngineSchema
import viaduct.engine.runtime.validation.Validator
import viaduct.graphql.utils.isParentField

class CheckersAreNotOnParentFields(
    private val schema: EngineSchema,
) : Validator<CheckerExecutorValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: CheckerExecutorValidationCtx) {
        val fieldName = ctx.fieldName ?: return
        if (schema.schema.getObjectType(ctx.typeName)?.getFieldDefinition(fieldName)?.isParentField() == true) {
            throw CheckerOnParentFieldException(
                "${ctx.checkerDescription} is registered on a @parent field. Access checkers may not be registered on @parent fields."
            )
        }
    }
}

private class CheckerOnParentFieldException(msg: String) : Exception(msg)
