package viaduct.api.internal

import graphql.schema.GraphQLInputObjectField
import graphql.schema.GraphQLType
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineSelectionSet
import viaduct.mapping.graphql.Conv
import viaduct.mapping.graphql.IR

/** Factory for creating [Conv]s that map between GRT and IR values. */
@InternalApi
interface GRTConvFactory {
    fun create(
        internalCtx: InternalContext,
        type: GraphQLType,
        selectionSet: EngineSelectionSet? = null,
        keyMapping: KeyMapping? = null,
    ): Conv<Any?, IR.Value>

    fun createForInputField(
        internalCtx: InternalContext,
        field: GraphQLInputObjectField,
    ): Conv<Any?, IR.Value>

    companion object {
        /** Returns a factory that always returns [conv], ignoring all arguments. */
        fun const(conv: Conv<Any?, IR.Value>): GRTConvFactory =
            object : GRTConvFactory {
                override fun create(
                    internalCtx: InternalContext,
                    type: GraphQLType,
                    selectionSet: EngineSelectionSet?,
                    keyMapping: KeyMapping?,
                ) = conv

                override fun createForInputField(
                    internalCtx: InternalContext,
                    field: GraphQLInputObjectField,
                ) = conv
            }
    }
}
