@file:Suppress("warnings")

package viaduct.api.grts

import viaduct.apiannotations.InternalApi
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData

@OptIn(InternalApi::class)
class Order(context: InternalContext, engineObject: EngineObject)
    : ObjectBase(context, engineObject), viaduct.api.types.Object,viaduct.api.grts.Node,viaduct.api.grts.Auditable,viaduct.api.grts.Timestamped,viaduct.api.grts.SearchHit,viaduct.api.types.NodeObject
{
          final override fun getId(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order> = TODO()
          final override fun getId(): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order> = TODO()
          final override fun getIdOrNull(alias: String?): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>? = TODO()
          final override fun getIdOrNull(): viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>? = TODO()

     fun getStatus(alias: String?): viaduct.api.grts.OrderStatus = TODO()
     fun getStatus(): viaduct.api.grts.OrderStatus = TODO()
     fun getStatusOrNull(alias: String?): viaduct.api.grts.OrderStatus? = TODO()
     fun getStatusOrNull(): viaduct.api.grts.OrderStatus? = TODO()

     fun getTotal(alias: String?): viaduct.api.grts.Money = TODO()
     fun getTotal(): viaduct.api.grts.Money = TODO()
     fun getTotalOrNull(alias: String?): viaduct.api.grts.Money? = TODO()
     fun getTotalOrNull(): viaduct.api.grts.Money? = TODO()

          final override fun getCreatedAt(alias: String?): kotlin.String = TODO()
          final override fun getCreatedAt(): kotlin.String = TODO()
          final override fun getCreatedAtOrNull(alias: String?): kotlin.String? = TODO()
          final override fun getCreatedAtOrNull(): kotlin.String? = TODO()

          final override fun getUpdatedAt(alias: String?): kotlin.String? = TODO()
          final override fun getUpdatedAt(): kotlin.String? = TODO()
          final override fun getUpdatedAtOrNull(alias: String?): kotlin.String? = TODO()
          final override fun getUpdatedAtOrNull(): kotlin.String? = TODO()

          final override fun getAuditTrail(alias: String?): kotlin.collections.List<kotlin.String>? = TODO()
          final override fun getAuditTrail(): kotlin.collections.List<kotlin.String>? = TODO()
          final override fun getAuditTrailOrNull(alias: String?): kotlin.collections.List<kotlin.String>? = TODO()
          final override fun getAuditTrailOrNull(): kotlin.collections.List<kotlin.String>? = TODO()

     fun getBuyer(alias: String?): viaduct.api.grts.User = TODO()
     fun getBuyer(): viaduct.api.grts.User = TODO()
     fun getBuyerOrNull(alias: String?): viaduct.api.grts.User? = TODO()
     fun getBuyerOrNull(): viaduct.api.grts.User? = TODO()


    fun toBuilder(): Builder =
        Builder(__context, __engineObject.type, toBuilderEOD())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): Order =
            Builder(context).apply(block).build()
    }

    class Builder : ObjectBase.Builder<Order> {
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

                  fun id(value: viaduct.api.globalid.GlobalID<viaduct.api.grts.Order>): Builder = TODO()

                  fun status(value: viaduct.api.grts.OrderStatus): Builder = TODO()

                  fun total(value: viaduct.api.grts.Money): Builder = TODO()

                  fun createdAt(value: kotlin.String): Builder = TODO()

                  fun updatedAt(value: kotlin.String?): Builder = TODO()

                  fun auditTrail(value: kotlin.collections.List<kotlin.String>?): Builder = TODO()

                  fun buyer(value: viaduct.api.grts.User): Builder = TODO()


        final override fun build(): Order = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Order> {
        override final val name = "Order"
        override final val kcls = viaduct.api.grts.Order::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Order> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Order> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Order.Reflection)

            final val id: viaduct.api.reflect.Field<viaduct.api.grts.Order> =
                viaduct.api.internal.FieldImpl("id", viaduct.api.grts.Order.Reflection)

            final val status: viaduct.api.reflect.CompositeField<viaduct.api.grts.Order, viaduct.api.grts.OrderStatus> =
                viaduct.api.internal.CompositeFieldImpl("status", viaduct.api.grts.Order.Reflection, viaduct.api.grts.OrderStatus.Reflection)

            final val total: viaduct.api.reflect.CompositeField<viaduct.api.grts.Order, viaduct.api.grts.Money> =
                viaduct.api.internal.CompositeFieldImpl("total", viaduct.api.grts.Order.Reflection, viaduct.api.grts.Money.Reflection)

            final val createdAt: viaduct.api.reflect.Field<viaduct.api.grts.Order> =
                viaduct.api.internal.FieldImpl("createdAt", viaduct.api.grts.Order.Reflection)

            final val updatedAt: viaduct.api.reflect.Field<viaduct.api.grts.Order> =
                viaduct.api.internal.FieldImpl("updatedAt", viaduct.api.grts.Order.Reflection)

            final val auditTrail: viaduct.api.reflect.Field<viaduct.api.grts.Order> =
                viaduct.api.internal.FieldImpl("auditTrail", viaduct.api.grts.Order.Reflection)

            final val buyer: viaduct.api.reflect.CompositeField<viaduct.api.grts.Order, viaduct.api.grts.User> =
                viaduct.api.internal.CompositeFieldImpl("buyer", viaduct.api.grts.Order.Reflection, viaduct.api.grts.User.Reflection)

    }

}