package viaduct.engine.runtime.tenantloading

import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.validation.Validator

/**
 * Rejects `@resolver(isSelective: true)` on mutation fields.
 *
 * A selective resolver may re-execute when additional fields are requested, which for a
 * mutation would re-run its side effects. Mutation fields live either on the root mutation
 * type or on a `@namespaceType` object reachable from it; both are guarded here.
 *
 * This is the engine-layer backstop for the codegen-time ban: it covers every authoring path
 * (Kotlin codegen, Java tenant API, and programmatically-built executors), because all executors
 * flow through tenant-load validation regardless of how they were produced.
 */
class SelectiveResolverNotAllowedOnMutations(
    private val schema: ViaductSchema,
) : Validator<FieldResolverExecutorValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun validate(ctx: FieldResolverExecutorValidationCtx) =
        ctx.run {
            if (!executor.isSelective) return
            val typeName = coord.first
            if (typeName == schema.schema.mutationType?.name || schema.isMutationNamespaceType(typeName)) {
                throw SelectiveResolverOnMutationException(
                    "@resolver(isSelective: true) is not supported on mutation field ${coord.first}.${coord.second}"
                )
            }
        }
}

private class SelectiveResolverOnMutationException(msg: String) : Exception(msg)
