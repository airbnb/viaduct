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
class CreateOrderInput internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
): InputLikeBase(), viaduct.api.types.Input {
    init {
       TODO()
    }

    val buyerId: kotlin.String get() = TODO()

    val color: viaduct.api.grts.Color? get() = TODO()

    val amounts: kotlin.collections.List<kotlin.Double>? get() = TODO()

    val note: kotlin.String? get() = TODO()


    fun toBuilder() = Builder(context, graphQLInputObjectType, this.inputData.toMutableMap())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): CreateOrderInput =
            Builder(context).apply(block).build()
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        override val inputData: MutableMap<String, Any?> = TODO()
    ) : InputLikeBase.Builder(), InputValueBuilder<CreateOrderInput> {

        constructor(context: ExecutionContext): this(
            context.internal,
            InputTypeFactory.inputObjectInputType("CreateOrderInput", context.internal.schema),
            mutableMapOf()
        )

        init {
            TODO()
        }

                    fun buyerId(value: kotlin.String): Builder = TODO()

                    fun color(value: viaduct.api.grts.Color?): Builder = TODO()

                    fun amounts(value: kotlin.collections.List<kotlin.Double>?): Builder = TODO()

                    fun note(value: kotlin.String?): Builder = TODO()


        final override fun build(): CreateOrderInput = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.CreateOrderInput> {
        override final val name = "CreateOrderInput"
        override final val kcls = viaduct.api.grts.CreateOrderInput::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.CreateOrderInput> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.CreateOrderInput> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.CreateOrderInput.Reflection)

            final val buyerId: viaduct.api.reflect.Field<viaduct.api.grts.CreateOrderInput> =
                viaduct.api.internal.FieldImpl("buyerId", viaduct.api.grts.CreateOrderInput.Reflection)

            final val color: viaduct.api.reflect.CompositeField<viaduct.api.grts.CreateOrderInput, viaduct.api.grts.Color> =
                viaduct.api.internal.CompositeFieldImpl("color", viaduct.api.grts.CreateOrderInput.Reflection, viaduct.api.grts.Color.Reflection)

            final val amounts: viaduct.api.reflect.Field<viaduct.api.grts.CreateOrderInput> =
                viaduct.api.internal.FieldImpl("amounts", viaduct.api.grts.CreateOrderInput.Reflection)

            final val note: viaduct.api.reflect.Field<viaduct.api.grts.CreateOrderInput> =
                viaduct.api.internal.FieldImpl("note", viaduct.api.grts.CreateOrderInput.Reflection)

    }

}