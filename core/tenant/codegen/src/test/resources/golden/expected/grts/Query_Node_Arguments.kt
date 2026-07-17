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
class Query_Node_Arguments internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
): InputLikeBase(), viaduct.api.types.Arguments {
    init {
       TODO()
    }

    val id: kotlin.String get() = TODO()


    fun toBuilder() = Builder(context, graphQLInputObjectType, this.inputData.toMutableMap())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): Query_Node_Arguments =
            Builder(context).apply(block).build()
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        override val inputData: MutableMap<String, Any?> = TODO()
    ) : InputLikeBase.Builder(), InputValueBuilder<Query_Node_Arguments> {

        constructor(context: ExecutionContext): this(
            context.internal,
            InputTypeFactory.argumentsInputType("Query_Node_Arguments", "Query", "node", context.internal.schema),
            mutableMapOf()
        )

        init {
            TODO()
        }

                    fun id(value: kotlin.String): Builder = TODO()


        final override fun build(): Query_Node_Arguments = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Query_Node_Arguments> {
        override final val name = "Query_Node_Arguments"
        override final val kcls = viaduct.api.grts.Query_Node_Arguments::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Query_Node_Arguments> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Query_Node_Arguments> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Query_Node_Arguments.Reflection)

            final val id: viaduct.api.reflect.Field<viaduct.api.grts.Query_Node_Arguments> =
                viaduct.api.internal.FieldImpl("id", viaduct.api.grts.Query_Node_Arguments.Reflection)

    }

}