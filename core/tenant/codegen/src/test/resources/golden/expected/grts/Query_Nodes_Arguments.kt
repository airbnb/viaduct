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
class Query_Nodes_Arguments internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
): InputLikeBase(), viaduct.api.types.Arguments {
    init {
       TODO()
    }

    val ids: kotlin.collections.List<kotlin.String> get() = TODO()


    fun toBuilder() = Builder(context, graphQLInputObjectType, this.inputData.toMutableMap())

    object of {
        operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): Query_Nodes_Arguments =
            Builder(context).apply(block).build()
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        override val inputData: MutableMap<String, Any?> = TODO()
    ) : InputLikeBase.Builder(), InputValueBuilder<Query_Nodes_Arguments> {

        constructor(context: ExecutionContext): this(
            context.internal,
            InputTypeFactory.argumentsInputType("Query_Nodes_Arguments", "Query", "nodes", context.internal.schema),
            mutableMapOf()
        )

        init {
            TODO()
        }

                    fun ids(value: kotlin.collections.List<kotlin.String>): Builder = TODO()


        final override fun build(): Query_Nodes_Arguments = TODO()
    }

    @OptIn(viaduct.apiannotations.InternalApi::class)
    object Reflection : viaduct.api.reflect.Type<viaduct.api.grts.Query_Nodes_Arguments> {
        override final val name = "Query_Nodes_Arguments"
        override final val kcls = viaduct.api.grts.Query_Nodes_Arguments::class
    }
    object Fields : viaduct.api.reflect.TypeFields<viaduct.api.grts.Query_Nodes_Arguments> {
            final val __typename: viaduct.api.reflect.Field<viaduct.api.grts.Query_Nodes_Arguments> =
                viaduct.api.internal.FieldImpl("__typename", viaduct.api.grts.Query_Nodes_Arguments.Reflection)

            final val ids: viaduct.api.reflect.Field<viaduct.api.grts.Query_Nodes_Arguments> =
                viaduct.api.internal.FieldImpl("ids", viaduct.api.grts.Query_Nodes_Arguments.Reflection)

    }

}