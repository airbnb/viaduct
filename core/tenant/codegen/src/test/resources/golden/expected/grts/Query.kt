@file:Suppress("warnings")

package viaduct.api.grts

import viaduct.apiannotations.InternalApi
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData

@OptIn(InternalApi::class)
class Query(context: InternalContext, engineObject: EngineObject)
    : ObjectBase(context, engineObject), viaduct.api.types.Object,viaduct.api.types.Query
{
     fun getOrder(alias: String?): viaduct.api.grts.Order? = TODO()
     fun getOrder(): viaduct.api.grts.Order? = TODO()
     fun getOrderOrNull(alias: String?): viaduct.api.grts.Order? = TODO()
     fun getOrderOrNull(): viaduct.api.grts.Order? = TODO()

     fun getTopUser(alias: String?): viaduct.api.grts.User? = TODO()
     fun getTopUser(): viaduct.api.grts.User? = TODO()
     fun getTopUserOrNull(alias: String?): viaduct.api.grts.User? = TODO()
     fun getTopUserOrNull(): viaduct.api.grts.User? = TODO()

     fun getPopularOrders(alias: String?): kotlin.collections.List<viaduct.api.grts.Order> = TODO()
     fun getPopularOrders(): kotlin.collections.List<viaduct.api.grts.Order> = TODO()
     fun getPopularOrdersOrNull(alias: String?): kotlin.collections.List<viaduct.api.grts.Order>? = TODO()
     fun getPopularOrdersOrNull(): kotlin.collections.List<viaduct.api.grts.Order>? = TODO()

     fun getTrendingUsers(alias: String?): kotlin.collections.List<viaduct.api.grts.User> = TODO()
     fun getTrendingUsers(): kotlin.collections.List<viaduct.api.grts.User> = TODO()
     fun getTrendingUsersOrNull(alias: String?): kotlin.collections.List<viaduct.api.grts.User>? = TODO()
     fun getTrendingUsersOrNull(): kotlin.collections.List<viaduct.api.grts.User>? = TODO()

     fun getOrdersConnection(alias: String?): viaduct.api.grts.OrderConnection? = TODO()
     fun getOrdersConnection(): viaduct.api.grts.OrderConnection? = TODO()
     fun getOrdersConnectionOrNull(alias: String?): viaduct.api.grts.OrderConnection? = TODO()
     fun getOrdersConnectionOrNull(): viaduct.api.grts.OrderConnection? = TODO()

     fun getNode(alias: String?): viaduct.api.grts.Node? = TODO()
     fun getNode(): viaduct.api.grts.Node? = TODO()
     fun getNodeOrNull(alias: String?): viaduct.api.grts.Node? = TODO()
     fun getNodeOrNull(): viaduct.api.grts.Node? = TODO()

     fun getNodes(alias: String?): kotlin.collections.List<viaduct.api.grts.Node?> = TODO()
     fun getNodes(): kotlin.collections.List<viaduct.api.grts.Node?> = TODO()
     fun getNodesOrNull(alias: String?): kotlin.collections.List<viaduct.api.grts.Node?>? = TODO()
     fun getNodesOrNull(): kotlin.collections.List<viaduct.api.grts.Node?>? = TODO()


    fun toBuilder(): Builder =
        Builder(__context, __engineObject.type, toBuilderEOD())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): Query =
            Builder(context).apply(block).build()
    }

    class Builder : ObjectBase.Builder<Query> {
        constructor(context: ExecutionContext)
            : super(
                context as InternalContext,
                TODO() as graphql.schema.GraphQLObjectType,
                null
            )

        internal constructor(
            context: InternalContext,
            type: graphql.schema.GraphQLObjectType,
            baseEngineObjectData: EngineObjectData.Sync
        ) : super(context, type, baseEngineObjectData)

                  fun order(value: viaduct.api.grts.Order?): Builder = TODO()

                  fun topUser(value: viaduct.api.grts.User?): Builder = TODO()

                  fun popularOrders(value: kotlin.collections.List<viaduct.api.grts.Order>): Builder = TODO()

                  fun trendingUsers(value: kotlin.collections.List<viaduct.api.grts.User>): Builder = TODO()

                  fun ordersConnection(value: viaduct.api.grts.OrderConnection?): Builder = TODO()

                  fun node(value: viaduct.api.grts.Node?): Builder = TODO()

                  fun nodes(value: kotlin.collections.List<viaduct.api.grts.Node?>): Builder = TODO()


        final override fun build(): Query = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Query> {
        override final val name = "Query"
        override final val kcls = viaduct.api.grts.Query::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Query> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Query> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Query.Reflection)

            final val order: viaduct.api.reflect.RootObjectField<viaduct.api.grts.Query, viaduct.api.grts.Order, viaduct.api.grts.Query_Order_Arguments> =
                viaduct.api.internal.RootObjectFieldImpl("order", viaduct.api.grts.Query.Reflection, viaduct.api.grts.Order.Reflection, listOf("order"))

            final val topUser: viaduct.api.reflect.RootObjectField<viaduct.api.grts.Query, viaduct.api.grts.User, viaduct.api.types.Arguments.NoArguments> =
                viaduct.api.internal.RootObjectFieldImpl("topUser", viaduct.api.grts.Query.Reflection, viaduct.api.grts.User.Reflection, listOf("topUser"))

            final val popularOrders: viaduct.api.reflect.CompositeField<viaduct.api.grts.Query, viaduct.api.grts.Order> =
                viaduct.api.internal.CompositeFieldImpl("popularOrders", viaduct.api.grts.Query.Reflection, viaduct.api.grts.Order.Reflection)

            final val trendingUsers: viaduct.api.reflect.CompositeField<viaduct.api.grts.Query, viaduct.api.grts.User> =
                viaduct.api.internal.CompositeFieldImpl("trendingUsers", viaduct.api.grts.Query.Reflection, viaduct.api.grts.User.Reflection)

            final val ordersConnection: viaduct.api.reflect.RootObjectField<viaduct.api.grts.Query, viaduct.api.grts.OrderConnection, viaduct.api.grts.Query_OrdersConnection_Arguments> =
                viaduct.api.internal.RootObjectFieldImpl("ordersConnection", viaduct.api.grts.Query.Reflection, viaduct.api.grts.OrderConnection.Reflection, listOf("ordersConnection"))

            final val node: viaduct.api.reflect.CompositeField<viaduct.api.grts.Query, viaduct.api.grts.Node> =
                viaduct.api.internal.CompositeFieldImpl("node", viaduct.api.grts.Query.Reflection, viaduct.api.grts.Node.Reflection)

            final val nodes: viaduct.api.reflect.CompositeField<viaduct.api.grts.Query, viaduct.api.grts.Node> =
                viaduct.api.internal.CompositeFieldImpl("nodes", viaduct.api.grts.Query.Reflection, viaduct.api.grts.Node.Reflection)

    }

}