@file:Suppress("warnings")

package viaduct.api.grts

import viaduct.apiannotations.InternalApi
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData

@OptIn(InternalApi::class)
class Money(context: InternalContext, engineObject: EngineObject)
    : ObjectBase(context, engineObject), viaduct.api.types.Object,viaduct.api.grts.SearchHit
{
     fun getAmountOrThrow(alias: String?): kotlin.Double = TODO()
     fun getAmountOrThrow(): kotlin.Double = TODO()
     fun getAmount(alias: String?): kotlin.Double = TODO()
     fun getAmount(): kotlin.Double = TODO()
     fun getAmountOrNull(alias: String?): kotlin.Double? = TODO()
     fun getAmountOrNull(): kotlin.Double? = TODO()

     fun getCurrencyOrThrow(alias: String?): kotlin.String = TODO()
     fun getCurrencyOrThrow(): kotlin.String = TODO()
     fun getCurrency(alias: String?): kotlin.String = TODO()
     fun getCurrency(): kotlin.String = TODO()
     fun getCurrencyOrNull(alias: String?): kotlin.String? = TODO()
     fun getCurrencyOrNull(): kotlin.String? = TODO()


    fun toBuilder(): Builder =
        Builder(__context, __engineObject.type, toBuilderEOD())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): Money =
            Builder(context).apply(block).build()
    }

    class Builder : ObjectBase.Builder<Money> {
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

                  fun amount(value: kotlin.Double): Builder = TODO()

                  fun currency(value: kotlin.String): Builder = TODO()


        final override fun build(): Money = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Money> {
        override final val name = "Money"
        override final val kcls = viaduct.api.grts.Money::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Money> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Money> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Money.Reflection)

            final val amount: viaduct.api.reflect.Field<viaduct.api.grts.Money> =
                viaduct.api.internal.FieldImpl("amount", viaduct.api.grts.Money.Reflection)

            final val currency: viaduct.api.reflect.Field<viaduct.api.grts.Money> =
                viaduct.api.internal.FieldImpl("currency", viaduct.api.grts.Money.Reflection)

    }

}