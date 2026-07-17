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
class Mutation_CreateOrder_Arguments internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
): InputLikeBase(), viaduct.api.types.Arguments {
    init {
       TODO()
    }

    val input: viaduct.api.grts.CreateOrderInput get() = TODO()


    fun toBuilder() = Builder(context, graphQLInputObjectType, this.inputData.toMutableMap())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): Mutation_CreateOrder_Arguments =
            Builder(context).apply(block).build()
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        override val inputData: MutableMap<String, Any?> = TODO()
    ) : InputLikeBase.Builder(), InputValueBuilder<Mutation_CreateOrder_Arguments> {

        constructor(context: ExecutionContext): this(
            context.internal,
            InputTypeFactory.argumentsInputType("Mutation_CreateOrder_Arguments", "Mutation", "createOrder", context.internal.schema),
            mutableMapOf()
        )

        init {
            TODO()
        }

                    fun input(value: viaduct.api.grts.CreateOrderInput): Builder = TODO()


        final override fun build(): Mutation_CreateOrder_Arguments = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Mutation_CreateOrder_Arguments> {
        override final val name = "Mutation_CreateOrder_Arguments"
        override final val kcls = viaduct.api.grts.Mutation_CreateOrder_Arguments::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Mutation_CreateOrder_Arguments> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Mutation_CreateOrder_Arguments> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Mutation_CreateOrder_Arguments.Reflection)

            final val input: viaduct.api.reflect.CompositeField<viaduct.api.grts.Mutation_CreateOrder_Arguments, viaduct.api.grts.CreateOrderInput> =
                viaduct.api.internal.CompositeFieldImpl("input", viaduct.api.grts.Mutation_CreateOrder_Arguments.Reflection, viaduct.api.grts.CreateOrderInput.Reflection)

    }

}