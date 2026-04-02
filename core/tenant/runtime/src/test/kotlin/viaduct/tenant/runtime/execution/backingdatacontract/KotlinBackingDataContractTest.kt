@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.backingdatacontract

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.backingdatacontract.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.backingdatacontract.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.BackingDataContractTest

data class BackingDataValue(val i: Int, val s: String)

class KotlinBackingDataContractTest : BackingDataContractTest() {
    @Resolver
    class Query_FooResolver : QueryResolvers.Foo() {
        override suspend fun resolve(ctx: Context) = Foo.Builder(ctx).build()
    }

    @Resolver
    class Foo_BackingDataValueResolver : FooResolvers.BackingDataValue() {
        override suspend fun resolve(ctx: Context) = BackingDataValue(10, "Hello, World!")
    }

    @Resolver("backingDataValue")
    class Foo_IValueResolver : FooResolvers.IValue() {
        override suspend fun resolve(ctx: Context) = ctx.objectValue.get<BackingDataValue>("backingDataValue", BackingDataValue::class).i
    }

    @Resolver(
        """
        fragment _ on Foo {
            backingDataValue
         }
        """
    )
    class Foo_SValueResolver : FooResolvers.SValue() {
        override suspend fun resolve(ctx: Context) = ctx.objectValue.get<BackingDataValue>("backingDataValue", BackingDataValue::class).s
    }
}
