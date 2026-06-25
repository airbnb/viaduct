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

}