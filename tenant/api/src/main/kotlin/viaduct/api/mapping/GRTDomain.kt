package viaduct.api.mapping

import viaduct.api.context.ExecutionContext
import viaduct.api.internal.GRTConv
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.internal
import viaduct.api.types.GRT
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.Domain
import viaduct.mapping.graphql.IR

/** A [Domain] that models [GRT] values */
class GRTDomain(val ctx: ExecutionContext) : Domain<GRT> {
    private val internalCtx = ctx.internal

    override val conv: Conv<GRT, IR.Value.Object> =
        Conv(
            forward = {
                val conv = when (it) {
                    is InputLikeBase -> GRTConv(internalCtx, it.graphQLInputObjectType)
                    is ObjectBase -> GRTConv(internalCtx, it.engineObject.graphQLObjectType)
                    else ->
                        throw IllegalArgumentException("Unsupported GRT type: ${it.javaClass}")
                }
                conv(it) as IR.Value.Object
            },
            inverse = {
                val typeName = it.name
                val type = requireNotNull(internalCtx.schema.schema.getType(typeName)) {
                    "Unknown type: $typeName"
                }
                val conv = GRTConv(internalCtx, type)
                conv.invert(it) as GRT
            },
            "GRTDomain"
        )
}
