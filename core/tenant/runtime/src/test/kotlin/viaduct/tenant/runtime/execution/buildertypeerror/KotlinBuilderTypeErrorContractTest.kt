@file:Suppress("UNCHECKED_CAST")

package viaduct.tenant.runtime.execution.buildertypeerror

import viaduct.api.Resolver
import viaduct.tenant.runtime.execution.buildertypeerror.resolverbases.QueryResolvers

class KotlinBuilderTypeErrorContractTest : BuilderTypeErrorContractTest() {
    @Resolver
    class Query_ContainerResolver : QueryResolvers.Container() {
        override suspend fun resolve(ctx: Context): Container {
            val item = Item.Builder(ctx).name("wrong-type").build()
            // Deliberately pass Item where Tag is expected, using an unchecked cast to bypass
            // the Kotlin compiler. This simulates the type erasure scenario: at the JVM level
            // List<Item> is identical to List<Tag>, so the typed setter accepts it without
            // complaint. The builder's runtime type check must catch this.
            val wrongTypedList = listOf(item) as List<Tag?>
            return Container.Builder(ctx).tags(wrongTypedList).build()
        }
    }
}
