@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.bootstrap.stringtointcoercion

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.engine.api.GraphQLBuildError
import viaduct.tenant.runtime.execution.variables.bootstrap.stringtointcoercion.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Tests for invalid GraphQL fragment syntax that causes bootstrap failures.
 * This test expects the tenant to fail to build due to invalid GraphQL syntax.
 */
class InvalidFragmentSyntaxFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | extend type Query {
        |   fromArgumentField(arg: Int!): Int @resolver
        |   intermediary(arg: Int!): Int @resolver
        |   fromVariablesProvider: Int @resolver
        | }
        | #END_SCHEMA
        """.trimMargin()

    // String to Int coercion failure - should fail at bootstrap
    @Resolver("intermediary(arg: ${'$'}intVar)")
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.get("intermediary", Int::class)

        @Variables("intVar:Int!")
        class StringToIntVars : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> = mapOf("intVar" to "not_an_integer")
        }
    }

    @Resolver
    class Query_IntermediaryResolver : QueryResolvers.Intermediary() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.arg
    }

    @Resolver
    class Query_FromArgumentFieldResolver : QueryResolvers.FromArgumentField() {
        override suspend fun resolve(ctx: Context): Int = ctx.arguments.arg
    }

    @Test
    @Disabled("https://app.asana.com/1/150975571430/project/1207604899751448/task/1210664713712227")
    fun `invalid fragment syntax fails at bootstrap time`() {
        var ex: Throwable? = null
        try {
            tryBuildViaductService()
        } catch (e: Exception) {
            ex = e.cause ?: e
        }

        requireNotNull(ex) { "Expected exception during tenant build" }

        assertTrue(ex is GraphQLBuildError, "Expected GraphQLBuildError but got ${ex::class.simpleName}")
    }
}
