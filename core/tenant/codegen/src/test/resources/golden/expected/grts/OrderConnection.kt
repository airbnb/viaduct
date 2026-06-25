@file:Suppress("warnings")

package viaduct.api.grts

import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.internal.ConnectionBuilder
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.types.ConnectionArguments
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData

@OptIn(InternalApi::class, ExperimentalApi::class)
class OrderConnection(context: InternalContext, engineObject: EngineObject)
    : ObjectBase(context, engineObject), viaduct.api.types.Object,viaduct.api.types.Connection<viaduct.api.grts.OrderEdge, viaduct.api.grts.Order>
{
     fun getEdges(alias: String?): kotlin.collections.List<viaduct.api.grts.OrderEdge> = TODO()
     fun getEdges(): kotlin.collections.List<viaduct.api.grts.OrderEdge> = TODO()
     fun getEdgesOrNull(alias: String?): kotlin.collections.List<viaduct.api.grts.OrderEdge>? = TODO()
     fun getEdgesOrNull(): kotlin.collections.List<viaduct.api.grts.OrderEdge>? = TODO()

     fun getPageInfo(alias: String?): viaduct.api.grts.PageInfo? = TODO()
     fun getPageInfo(): viaduct.api.grts.PageInfo? = TODO()
     fun getPageInfoOrNull(alias: String?): viaduct.api.grts.PageInfo? = TODO()
     fun getPageInfoOrNull(): viaduct.api.grts.PageInfo? = TODO()


    fun toBuilder(): Builder =
        Builder(__context, __engineObject.type, toBuilderEOD())

    object of {
        operator fun invoke(context: ConnectionFieldExecutionContext<*, *, out ConnectionArguments, OrderConnection>, block: Builder.() -> Unit): OrderConnection =
            Builder(context).apply(block).build()
    }

    class Builder : ConnectionBuilder<OrderConnection, viaduct.api.grts.OrderEdge, viaduct.api.grts.Order> {
        constructor(context: ConnectionFieldExecutionContext<*, *, out ConnectionArguments, OrderConnection>)
            : super(
                context,
                TODO() as graphql.schema.GraphQLObjectType,
                null,
                viaduct.api.grts.OrderEdge.Reflection
            )

        internal constructor(
            context: InternalContext,
            type: graphql.schema.GraphQLObjectType,
            baseEngineObjectData: EngineObjectData.Sync
        ) : super(
                context as ConnectionFieldExecutionContext<*, *, out ConnectionArguments, OrderConnection>,
                type,
                baseEngineObjectData,
                viaduct.api.grts.OrderEdge.Reflection
            )

                  fun edges(value: kotlin.collections.List<viaduct.api.grts.OrderEdge>): Builder = TODO()

                  fun pageInfo(value: viaduct.api.grts.PageInfo?): Builder = TODO()


        final override fun build(): OrderConnection {
            @Suppress("UNUSED_EXPRESSION")
            ObjectBase.Builder::class
            return TODO()
        }
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.OrderConnection> {
        override final val name = "OrderConnection"
        override final val kcls = viaduct.api.grts.OrderConnection::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.OrderConnection> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.OrderConnection> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.OrderConnection.Reflection)

            final val edges: viaduct.api.reflect.CompositeField<viaduct.api.grts.OrderConnection, viaduct.api.grts.OrderEdge> =
                viaduct.api.internal.CompositeFieldImpl("edges", viaduct.api.grts.OrderConnection.Reflection, viaduct.api.grts.OrderEdge.Reflection)

            final val pageInfo: viaduct.api.reflect.CompositeField<viaduct.api.grts.OrderConnection, viaduct.api.grts.PageInfo> =
                viaduct.api.internal.CompositeFieldImpl("pageInfo", viaduct.api.grts.OrderConnection.Reflection, viaduct.api.grts.PageInfo.Reflection)

    }

}