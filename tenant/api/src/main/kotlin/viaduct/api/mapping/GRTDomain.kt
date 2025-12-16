package viaduct.api.mapping

import viaduct.api.context.ExecutionContext
import viaduct.api.internal.GRTConv
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.internal.InternalSelectionSet
import viaduct.api.internal.KeyMapping
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.internal
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.api.types.GRT
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.api.RawSelectionSet
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.Domain
import viaduct.mapping.graphql.IR

/** A [Domain] that models [GRT] values */
@ExperimentalApi
class GRTDomain<T : GRT> private constructor(
    private val ctx: InternalContext,
    private val selectionSet: RawSelectionSet?,
    private val keyMapping: KeyMapping
) : Domain<T> {
    override val conv: Conv<T, IR.Value.Object> =
        Conv(
            forward = {
                val conv = when (it) {
                    is InputLikeBase -> GRTConv(
                        internalCtx = ctx,
                        type = it.graphQLInputObjectType,
                        selectionSet = null,
                        keyMapping = keyMapping,
                    )
                    is ObjectBase -> GRTConv(
                        internalCtx = ctx,
                        type = it.engineObject.graphQLObjectType,
                        selectionSet = selectionSet,
                        keyMapping = keyMapping
                    )
                    else ->
                        throw IllegalArgumentException("Unsupported GRT type: ${it.javaClass}")
                }
                conv(it) as IR.Value.Object
            },
            inverse = {
                val typeName = it.name
                val type = requireNotNull(ctx.schema.schema.getType(typeName)) {
                    "Unknown type: $typeName"
                }
                val conv = GRTConv(ctx, type, selectionSet, keyMapping)
                @Suppress("UNCHECKED_CAST")
                conv.invert(it) as T
            },
            "GRTDomain"
        )

    companion object {
        /** Create a [GRTDomain] that maps values for the provided [selectionSet] */
        fun <T : CompositeOutput> forSelectionSet(
            ctx: ExecutionContext,
            selectionSet: SelectionSet<T>,
            keyMapping: KeyMapping = KeyMapping.SelectionToSelection
        ): Domain<T> =
            GRTDomain(
                ctx.internal,
                (selectionSet as InternalSelectionSet).rawSelectionSet,
                keyMapping
            )

        /**
         * Create a [GRTDomain] that maps values for the provided type [T].
         *
         * The [GRTDomain] returned by this method does not support mapping aliased fields.
         * If support for aliased fields is needed, see [forSelectionSet].
         */
        fun <T : GRT> forType(ctx: ExecutionContext): Domain<T> =
            GRTDomain(
                ctx.internal,
                null,
                KeyMapping.FieldNameToFieldName
            )

        /**
         * Create a [GRTDomain] that maps values for any [GRT] value.
         *
         * The [GRTDomain] returned by this method does not support mapping aliased fields.
         * If support for aliased fields is needed, see [forSelectionSet].
         */
        operator fun invoke(ctx: ExecutionContext): Domain<GRT> =
            GRTDomain(
                ctx.internal,
                null,
                KeyMapping.FieldNameToFieldName
            )
    }
}
