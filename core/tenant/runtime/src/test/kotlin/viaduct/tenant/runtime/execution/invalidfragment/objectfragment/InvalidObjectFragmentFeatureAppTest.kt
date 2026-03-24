@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.invalidfragment.objectfragment

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.engine.api.GraphQLBuildError
import viaduct.tenant.runtime.execution.invalidfragment.objectfragment.resolverbases.FooResolvers
import viaduct.tenant.runtime.execution.invalidfragment.objectfragment.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

// Test for a object value fragment that is invalid
class InvalidObjectFragmentFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | type Foo {
        |   bar: String @resolver
        |   baz: String @resolver
        | }
        | extend type Query {
        |   greeting: Foo @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

    @Resolver("horse")
    class Query_GreetingResolver : QueryResolvers.Greeting() {
        override suspend fun resolve(ctx: Context) = Foo.Builder(ctx).build()
    }

    @Resolver
    class Foo_BazResolver : FooResolvers.Baz() {
        override suspend fun resolve(ctx: Context) = "world"
    }

    // Delegates to baz using selection list syntax
    @Resolver
    class Foo_BarResolver : FooResolvers.Bar() {
        override suspend fun resolve(ctx: Context) = ctx.objectValue.get<String>("baz", String::class)
    }

    @Test
    fun `invalid resolver does not cause a Guice exception`() {
        var ex: Throwable? = null
        try {
            tryBuildViaductService()
        } catch (e: Exception) {
            ex = e.cause ?: e
        }
        assertTrue(ex is GraphQLBuildError)
    }
}
