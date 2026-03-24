@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.bootstrap.objecttypes

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.engine.api.GraphQLBuildError
import viaduct.tenant.runtime.execution.variables.bootstrap.objecttypes.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Tests for @Variables referencing object types that should cause bootstrap failures.
 * Note: In the current implementation, type validation may occur at query time rather than bootstrap time.
 * This test uses invalid syntax to ensure bootstrap failure for demonstration purposes.
 */
class ObjectTypesFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | type Book {
        |   title: String
        |   author: String
        | }
        | extend type Query {
        |   fromArgumentField(arg: Int!): Int @resolver
        |   intermediary(arg: Int!): Int @resolver
        |   fromVariablesProvider: Int @resolver
        | }
        | #END_SCHEMA
        """.trimMargin()

    // Object type in @Variables - should fail at bootstrap (object types are not valid input types)
    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}someVar)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.get("intermediary", Int::class)

        @Variables("someVar:Book!")
        class ObjectTypeVars : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> = mapOf("someVar" to 42)
        }
    }

    // Need these resolvers to satisfy the schema
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
    fun `object type in variables fails at bootstrap time`() {
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
