@file:Suppress("warnings")

package viaduct.api.grts

import graphql.schema.GraphQLInputObjectType
import viaduct.apiannotations.InternalApi
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InputTypeFactory
import viaduct.api.internal.InputValueBuilder
import viaduct.api.internal.InternalContext
import viaduct.api.internal.internal
import viaduct.api.internal.InputLikeBase
import viaduct.api.types.Input

@OptIn(InternalApi::class)
class Query_OrdersConnection_Arguments internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
): InputLikeBase(), viaduct.api.types.Arguments, viaduct.api.types.ForwardConnectionArguments {
    init {
       TODO()
    }

            final override val first: kotlin.Int get() = TODO()

            final override val after: kotlin.String? get() = TODO()


    fun toBuilder() = Builder(context, graphQLInputObjectType, this.inputData.toMutableMap())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): Query_OrdersConnection_Arguments =
            Builder(context).apply(block).build()
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        override val inputData: MutableMap<String, Any?> = TODO()
    ) : InputLikeBase.Builder(), InputValueBuilder<Query_OrdersConnection_Arguments> {

        constructor(context: ExecutionContext): this(
            context.internal,
            InputTypeFactory.argumentsInputType("Query_OrdersConnection_Arguments", "Query", "ordersConnection", context.internal.schema),
            mutableMapOf()
        )

        init {
            TODO()
        }

                    fun first(value: kotlin.Int): Builder = TODO()

                    fun after(value: kotlin.String?): Builder = TODO()


        final override fun build(): Query_OrdersConnection_Arguments = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Query_OrdersConnection_Arguments> {
        override final val name = "Query_OrdersConnection_Arguments"
        override final val kcls = viaduct.api.grts.Query_OrdersConnection_Arguments::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Query_OrdersConnection_Arguments> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Query_OrdersConnection_Arguments> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Query_OrdersConnection_Arguments.Reflection)

            final val first: viaduct.api.reflect.Field<viaduct.api.grts.Query_OrdersConnection_Arguments> =
                viaduct.api.internal.FieldImpl("first", viaduct.api.grts.Query_OrdersConnection_Arguments.Reflection)

            final val after: viaduct.api.reflect.Field<viaduct.api.grts.Query_OrdersConnection_Arguments> =
                viaduct.api.internal.FieldImpl("after", viaduct.api.grts.Query_OrdersConnection_Arguments.Reflection)

    }

}