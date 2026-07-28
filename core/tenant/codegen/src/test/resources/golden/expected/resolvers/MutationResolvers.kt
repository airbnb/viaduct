package com.example.tenant.resolverbases

import graphql.schema.GraphQLSchema
import viaduct.apiannotations.ExperimentalApi
import viaduct.apiannotations.InternalApi
import viaduct.api.context.FieldExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ResolverFor
import viaduct.api.types.Arguments.NoArguments
import viaduct.api.types.CompositeOutput
import viaduct.api.FieldValue

@OptIn(InternalApi::class, ExperimentalApi::class)
object MutationResolvers {
    @ResolverFor(typeName = "Mutation", fieldName = "createOrder", isSelective = false, isBatching = false)
    abstract class CreateOrder : viaduct.api.ResolverBase<viaduct.api.grts.Order?>, viaduct.api.MutationResolverBase<viaduct.api.grts.Query, viaduct.api.grts.Mutation, viaduct.api.grts.Mutation_CreateOrder_Arguments, viaduct.api.grts.Order?>, viaduct.api.internal.BaseUnbatchedFieldResolver {
        class Context(
            private val inner: viaduct.api.context.MutationFieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Mutation, viaduct.api.grts.Mutation_CreateOrder_Arguments, viaduct.api.grts.Order>
        ) : viaduct.api.context.MutationFieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Mutation, viaduct.api.grts.Mutation_CreateOrder_Arguments, viaduct.api.grts.Order> by inner, InternalContext by (inner as InternalContext) {
        }
        abstract suspend fun resolve(ctx: Context): viaduct.api.grts.Order?

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldResolver(
            context: viaduct.api.context.BaseFieldExecutionContext<*, *, *>
        ): Any? = resolve(Context(context as viaduct.api.context.MutationFieldExecutionContext<viaduct.api.grts.Query, viaduct.api.grts.Mutation, viaduct.api.grts.Mutation_CreateOrder_Arguments, viaduct.api.grts.Order>))
    }
}