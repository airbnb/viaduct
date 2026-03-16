@file:Suppress("unused", "ClassName", "PackageDirectoryMismatch")

package inludedirective.featurapps

import inludedirective.featurapps.resolverbases.FooResolvers
import inludedirective.featurapps.resolverbases.QueryResolvers
import inludedirective.featurapps.resolverbases.ThrowerResolvers
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class IncludeDirectiveFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
    | #START_SCHEMA
    | extend type Query {
    |  foo: Foo @resolver
    |  thrower: Thrower @resolver
    |  booleanValue: Boolean @resolver
    | }
    |
    | type Thrower {
    |  willThrow: Int @resolver
    | }
    |
    | type Foo {
    |   intValue: Int @resolver
    |   sValue: String @resolver
    | }
    | #END_SCHEMA
    """.trimMargin()

    // Tenant provided resolvers

    @Resolver
    class Query_FooResolver : QueryResolvers.Foo() {
        override suspend fun resolve(ctx: Context): Foo {
            return Foo.Builder(ctx).build()
        }
    }

    @Resolver
    class Query_BooleanResolver : QueryResolvers.BooleanValue() {
        override suspend fun resolve(ctx: Context): Boolean {
            return false
        }
    }

    @Resolver
    class Foo_IValueResolver : FooResolvers.IntValue() {
        override suspend fun resolve(ctx: Context): Int {
            return 10
        }
    }

    @Resolver
    class Query_ThrowerResolver : QueryResolvers.Thrower() {
        override suspend fun resolve(ctx: Context): Thrower {
            return Thrower.Builder(ctx).build()
        }
    }

    @Resolver
    class Thrower_WillThrowResolver : ThrowerResolvers.WillThrow() {
        override suspend fun resolve(ctx: Context): Int {
            throw RuntimeException("asd")
        }
    }

    @Resolver
    class Foo_SValueResolver : FooResolvers.SValue() {
        override suspend fun resolve(ctx: Context): String {
            return "result value"
        }
    }

    @Test
    fun `using include directive as false`() {
        execute(
            query = """
                query {
                    foo @include(if:false) {
                      intValue
                      sValue
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {}
        }
    }

    @Test
    fun `using include directive as true`() {
        execute(
            query = """
                query {
                    foo @include(if:true) {
                      intValue
                      sValue
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "foo" to {
                    "intValue" to 10
                    "sValue" to "result value"
                }
            }
        }
    }

    @Test
    fun `using include directive will not call @resolver even if it throws`() {
        execute(
            query = """
                query {
                    foo @include(if:true) {
                      intValue
                      sValue
                    }
                    thrower @include(if:false) {
                        willThrow
                    }
                 }
            """.trimIndent()
        ).assertEquals {
            "data" to {
                "foo" to {
                    "intValue" to 10
                    "sValue" to "result value"
                }
            }
        }
    }

    @Test
    fun `using include as a given parameter from another @resolver`() {
        execute(
            query = """
                query MyQuery(${'$'}includeFoo: Boolean!){
                    booleanValue
                    foo @include(if: ${'$'}includeFoo) {
                      intValue
                      sValue
                    }
                    thrower @include(if:false) {
                        willThrow
                    }
                 }
            """.trimIndent(),
            variables = mapOf(
                "includeFoo" to false
            )
        ).assertEquals {
            "data" to {
                "booleanValue" to false
            }
        }
    }
}
