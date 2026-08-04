@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.batchresolver.errorhandling

import com.google.inject.AbstractModule
import com.google.inject.Module
import javax.inject.Inject
import viaduct.api.FieldValue
import viaduct.api.resolver.Resolver
import viaduct.tenant.runtime.execution.batchresolver.errorhandling.resolverbases.NodeResolvers
import viaduct.tenant.runtime.execution.batchresolver.errorhandling.resolverbases.QueryResolvers

class BatchResolverErrorHandlingFeatureAppTest : BatchResolverErrorHandlingContractTest() {
    override fun guiceModules(): List<Module> =
        listOf(
            object : AbstractModule() {
                override fun configure() {
                    bind(BatchResolverErrorHandlingContractTest::class.java).toInstance(this@BatchResolverErrorHandlingFeatureAppTest)
                }
            }
        )

    @Resolver
    class Query_FooResolver : QueryResolvers.Foo() {
        override suspend fun resolve(ctx: Context): Foo {
            return ctx.nodeRef(ctx.arguments.id)
        }
    }

    @Resolver
    class FooResolver
        @Inject
        constructor(
            private val test: BatchResolverErrorHandlingContractTest
        ) : NodeResolvers.Foo() {
            override suspend fun batchResolve(contexts: List<Context>): Map<Context, FieldValue<Foo>> {
                val results = contexts.associateWith { ctx ->
                    val selections = ctx.selections().toString()
                    FieldValue.Companion.ofValue(
                        Foo.Builder(ctx)
                            .id(ctx.id)
                            .a(selections)
                            .b("test-b-value")
                            .c("test-c-value")
                            .build()
                    )
                }

                return results.filterKeys { it.id.internalID != test.internalIdToOmit }
            }
        }
}
