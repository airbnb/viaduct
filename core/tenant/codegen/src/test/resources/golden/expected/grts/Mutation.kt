@file:Suppress("warnings")

package viaduct.api.grts

import viaduct.apiannotations.InternalApi
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData

@OptIn(InternalApi::class)
class Mutation(context: InternalContext, engineObject: EngineObject)
    : ObjectBase(context, engineObject), viaduct.api.types.Object,viaduct.api.types.Mutation
{
     fun getCreateOrder(alias: String?): viaduct.api.grts.Order? = TODO()
     fun getCreateOrder(): viaduct.api.grts.Order? = TODO()
     fun getCreateOrderOrNull(alias: String?): viaduct.api.grts.Order? = TODO()
     fun getCreateOrderOrNull(): viaduct.api.grts.Order? = TODO()


    fun toBuilder(): Builder =
        Builder(__context, __engineObject.type, toBuilderEOD())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): Mutation =
            Builder(context).apply(block).build()
    }

    class Builder : ObjectBase.Builder<Mutation> {
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

                  fun createOrder(value: viaduct.api.grts.Order?): Builder = TODO()


        final override fun build(): Mutation = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Mutation> {
        override final val name = "Mutation"
        override final val kcls = viaduct.api.grts.Mutation::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Mutation> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Mutation> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Mutation.Reflection)

            final val createOrder: viaduct.api.reflect.CompositeField<viaduct.api.grts.Mutation, viaduct.api.grts.Order> =
                viaduct.api.internal.CompositeFieldImpl("createOrder", viaduct.api.grts.Mutation.Reflection, viaduct.api.grts.Order.Reflection)

    }

}