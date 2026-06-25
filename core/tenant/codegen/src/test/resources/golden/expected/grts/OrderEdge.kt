@file:Suppress("warnings")

package viaduct.api.grts

import viaduct.apiannotations.InternalApi
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData

@OptIn(InternalApi::class)
class OrderEdge(context: InternalContext, engineObject: EngineObject)
    : ObjectBase(context, engineObject), viaduct.api.types.Object,viaduct.api.types.Edge<viaduct.api.grts.Order>
{
     fun getCursor(alias: String?): kotlin.String = TODO()
     fun getCursor(): kotlin.String = TODO()
     fun getCursorOrNull(alias: String?): kotlin.String? = TODO()
     fun getCursorOrNull(): kotlin.String? = TODO()

     fun getNode(alias: String?): viaduct.api.grts.Order? = TODO()
     fun getNode(): viaduct.api.grts.Order? = TODO()
     fun getNodeOrNull(alias: String?): viaduct.api.grts.Order? = TODO()
     fun getNodeOrNull(): viaduct.api.grts.Order? = TODO()


    fun toBuilder(): Builder =
        Builder(__context, __engineObject.type, toBuilderEOD())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): OrderEdge =
            Builder(context).apply(block).build()
    }

    class Builder : ObjectBase.Builder<OrderEdge> {
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

                  fun cursor(value: kotlin.String): Builder = TODO()

                  fun node(value: viaduct.api.grts.Order?): Builder = TODO()


        final override fun build(): OrderEdge = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.OrderEdge> {
        override final val name = "OrderEdge"
        override final val kcls = viaduct.api.grts.OrderEdge::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.OrderEdge> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.OrderEdge> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.OrderEdge.Reflection)

            final val cursor: viaduct.api.reflect.Field<viaduct.api.grts.OrderEdge> =
                viaduct.api.internal.FieldImpl("cursor", viaduct.api.grts.OrderEdge.Reflection)

            final val node: viaduct.api.reflect.CompositeField<viaduct.api.grts.OrderEdge, viaduct.api.grts.Order> =
                viaduct.api.internal.CompositeFieldImpl("node", viaduct.api.grts.OrderEdge.Reflection, viaduct.api.grts.Order.Reflection)

    }

}