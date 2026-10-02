@file:Suppress("unused")

package viaduct.tenant.runtime.execution.parentfields

import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.parentfields.resolverbases.ChildResolvers
import viaduct.tenant.runtime.execution.parentfields.resolverbases.ParentResolvers
import viaduct.tenant.runtime.execution.parentfields.resolverbases.QueryResolvers

class ParentFieldsFeatureAppTest : ParentFieldsContractTest() {
    @Resolver
    class Parents : QueryResolvers.Parents() {
        override suspend fun resolve(ctx: Context): List<Parent> =
            ctx.arguments.names.orEmpty().map { name ->
                Parent.Builder(ctx).name(name).build()
            }
    }

    @Resolver
    class FirstChild : ParentResolvers.FirstChild() {
        override suspend fun resolve(ctx: Context): Child = Child.Builder(ctx).build()
    }

    @Resolver
    class SecondChild : ParentResolvers.SecondChild() {
        override suspend fun resolve(ctx: Context): Child = Child.Builder(ctx).build()
    }

    @Resolver("parent { name }")
    class ParentName : ChildResolvers.ParentName() {
        override suspend fun resolve(ctx: Context): String? = ctx.getObjectValue().getParentOrThrow()!!.getNameOrThrow()
    }
}
