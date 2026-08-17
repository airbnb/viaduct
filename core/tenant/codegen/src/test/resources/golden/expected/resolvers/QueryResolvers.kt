package com.example.tenant.resolverbases

import graphql.schema.GraphQLSchema
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ResolverFor
import viaduct.api.types.Arguments.NoArguments
import viaduct.api.types.CompositeOutput
import viaduct.api.FieldValue

@OptIn(InternalApi::class, ExperimentalApi::class)
object QueryResolvers {
    @ResolverFor(typeName = "Query", fieldName = "order", isSelective = false, isBatching = false)
    abstract class Order : viaduct.api.ResolverBase<viaduct.api.grts.Order?>, viaduct.api.FieldResolverBase<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Order_Arguments, viaduct.api.grts.Order?>, viaduct.api.internal.BaseUnbatchedFieldResolver {
        class Context(
            private val inner: viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Order_Arguments, viaduct.api.grts.Order>
        ) : viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Order_Arguments, viaduct.api.grts.Order> by inner, InternalContext by (inner as InternalContext) {
        }
        abstract suspend fun resolve(ctx: Context): viaduct.api.grts.Order?

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldResolver(
            context: viaduct.api.context.BaseFieldExecutionContext<*, *, *>
        ): Any? = resolve(Context(context as viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Order_Arguments, viaduct.api.grts.Order>))
    }
    @ResolverFor(typeName = "Query", fieldName = "topUser", isSelective = true, isBatching = false)
    abstract class TopUser : viaduct.api.ResolverBase<viaduct.api.grts.User?>, viaduct.api.FieldResolverBase<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.User?>, viaduct.api.internal.BaseUnbatchedFieldResolver {
        class Context(
            private val inner: viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.User>
        ) : viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.User> by inner, viaduct.api.context.SelectiveFieldExecutionContext<viaduct.api.grts.User>, viaduct.api.context.ResolverOwnedSelectionsContext<viaduct.api.grts.User>, InternalContext by (inner as InternalContext) {
            @Suppress("UNCHECKED_CAST")
            override fun selections(): viaduct.api.select.SelectionSet<viaduct.api.grts.User> =
                (inner as viaduct.api.context.SelectiveFieldExecutionContext<viaduct.api.grts.User>).selections()

            @Suppress("UNCHECKED_CAST")
            override fun ownedSelections(): viaduct.api.select.SelectionSet<viaduct.api.grts.User> =
                (inner as viaduct.api.context.ResolverOwnedSelectionsContext<viaduct.api.grts.User>).ownedSelections()
        }
        abstract suspend fun resolve(ctx: Context): viaduct.api.grts.User?

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldResolver(
            context: viaduct.api.context.BaseFieldExecutionContext<*, *, *>
        ): Any? = resolve(Context(context as viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.User>))
    }
    @ResolverFor(typeName = "Query", fieldName = "popularOrders", isSelective = false, isBatching = true)
    abstract class PopularOrders : viaduct.api.ResolverBase<kotlin.collections.List<viaduct.api.grts.Order>>, viaduct.api.FieldResolverBase<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, kotlin.collections.List<viaduct.api.grts.Order>>, viaduct.api.internal.BaseBatchedFieldResolver {
        class Context(
            private val inner: viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.Order>
        ) : viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.Order> by inner, InternalContext by (inner as InternalContext) {
        }
        abstract suspend fun batchResolve(contexts: List<Context>): List<FieldValue<kotlin.collections.List<viaduct.api.grts.Order>>>

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldBatchResolver(
            contexts: List<viaduct.api.context.BaseFieldExecutionContext<*, *, *>>
        ): Any? = batchResolve(contexts.map { Context(it as viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.Order>) })
    }
    @ResolverFor(typeName = "Query", fieldName = "trendingUsers", isSelective = true, isBatching = true)
    abstract class TrendingUsers : viaduct.api.ResolverBase<kotlin.collections.List<viaduct.api.grts.User>>, viaduct.api.FieldResolverBase<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, kotlin.collections.List<viaduct.api.grts.User>>, viaduct.api.internal.BaseBatchedFieldResolver {
        class Context(
            private val inner: viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.User>
        ) : viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.User> by inner, viaduct.api.context.SelectiveFieldExecutionContext<viaduct.api.grts.User>, viaduct.api.context.ResolverOwnedSelectionsContext<viaduct.api.grts.User>, InternalContext by (inner as InternalContext) {
            @Suppress("UNCHECKED_CAST")
            override fun selections(): viaduct.api.select.SelectionSet<viaduct.api.grts.User> =
                (inner as viaduct.api.context.SelectiveFieldExecutionContext<viaduct.api.grts.User>).selections()

            @Suppress("UNCHECKED_CAST")
            override fun ownedSelections(): viaduct.api.select.SelectionSet<viaduct.api.grts.User> =
                (inner as viaduct.api.context.ResolverOwnedSelectionsContext<viaduct.api.grts.User>).ownedSelections()
        }
        abstract suspend fun batchResolve(contexts: List<Context>): List<FieldValue<kotlin.collections.List<viaduct.api.grts.User>>>

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldBatchResolver(
            contexts: List<viaduct.api.context.BaseFieldExecutionContext<*, *, *>>
        ): Any? = batchResolve(contexts.map { Context(it as viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.types.Arguments.NoArguments, viaduct.api.grts.User>) })
    }
    @ResolverFor(typeName = "Query", fieldName = "ordersConnection", isSelective = false, isBatching = false)
    abstract class OrdersConnection : viaduct.api.ResolverBase<viaduct.api.grts.OrderConnection?>, viaduct.api.ConnectionResolverBase<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_OrdersConnection_Arguments, viaduct.api.grts.OrderConnection?>, viaduct.api.internal.BaseUnbatchedFieldResolver {
        class Context(
            private val inner: viaduct.api.context.ConnectionFieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_OrdersConnection_Arguments, viaduct.api.grts.OrderConnection>
        ) : viaduct.api.context.ConnectionFieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_OrdersConnection_Arguments, viaduct.api.grts.OrderConnection> by inner, InternalContext by (inner as InternalContext) {
        }
        abstract suspend fun resolve(ctx: Context): viaduct.api.grts.OrderConnection?

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldResolver(
            context: viaduct.api.context.BaseFieldExecutionContext<*, *, *>
        ): Any? = resolve(Context(context as viaduct.api.context.ConnectionFieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_OrdersConnection_Arguments, viaduct.api.grts.OrderConnection>))
    }
    @ResolverFor(typeName = "Query", fieldName = "node", isSelective = false, isBatching = false)
    abstract class Node : viaduct.api.ResolverBase<viaduct.api.grts.Node?>, viaduct.api.FieldResolverBase<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Node_Arguments, viaduct.api.grts.Node?>, viaduct.api.internal.BaseUnbatchedFieldResolver {
        class Context(
            private val inner: viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Node_Arguments, viaduct.api.grts.Node>
        ) : viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Node_Arguments, viaduct.api.grts.Node> by inner, InternalContext by (inner as InternalContext) {
        }
        abstract suspend fun resolve(ctx: Context): viaduct.api.grts.Node?

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldResolver(
            context: viaduct.api.context.BaseFieldExecutionContext<*, *, *>
        ): Any? = resolve(Context(context as viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Node_Arguments, viaduct.api.grts.Node>))
    }
    @ResolverFor(typeName = "Query", fieldName = "nodes", isSelective = false, isBatching = false)
    abstract class Nodes : viaduct.api.ResolverBase<kotlin.collections.List<viaduct.api.grts.Node?>>, viaduct.api.FieldResolverBase<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Nodes_Arguments, kotlin.collections.List<viaduct.api.grts.Node?>>, viaduct.api.internal.BaseUnbatchedFieldResolver {
        class Context(
            private val inner: viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Nodes_Arguments, viaduct.api.grts.Node>
        ) : viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Nodes_Arguments, viaduct.api.grts.Node> by inner, InternalContext by (inner as InternalContext) {
        }
        abstract suspend fun resolve(ctx: Context): kotlin.collections.List<viaduct.api.grts.Node?>

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldResolver(
            context: viaduct.api.context.BaseFieldExecutionContext<*, *, *>
        ): Any? = resolve(Context(context as viaduct.api.context.FieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Query, viaduct.api.grts.Query_Nodes_Arguments, viaduct.api.grts.Node>))
    }
}