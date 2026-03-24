package viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.tenant.runtime.execution.variables.bootstrap.oneofviolation.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Tests for @oneOf input validation that should cause runtime failures.
 * This test expects the tenant to build successfully but queries to fail at runtime due to oneof violations.
 */
class TempOneOfViolationFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | input OneofInput @oneOf {
        |   stringValue: String
        |   intValue: Int
        | }
        | extend type Query {
        |   fromArgumentField(arg: OneofInput!): String @resolver
        |   intermediary(arg: OneofInput!): String @resolver
        |   fromVariablesProvider: String @resolver
        | }
        | #END_SCHEMA
        """.trimMargin()

    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}oneofVar)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): String? = ctx.objectValue.getIntermediary()

        @Variables("oneofVar: OneofInput!")
        class OneOfViolationVars : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> =
                mapOf(
                    "oneofVar" to mapOf(
                        "stringValue" to "test",
                        "intValue" to 42
                    )
                )
        }
    }

    @Resolver
    class Query_IntermediaryResolver : QueryResolvers.Intermediary() {
        override suspend fun resolve(ctx: Context): String = ctx.arguments.arg.toString()
    }

    @Resolver
    class Query_FromArgumentFieldResolver : QueryResolvers.FromArgumentField() {
        override suspend fun resolve(ctx: Context): String = ctx.arguments.arg.toString()
    }

    @Test
    fun `oneof violation fails at runtime`() {
        val result = execute("query { fromVariablesProvider }")

        // If we get here, check for errors in the result
        assertTrue(result.errors?.isNotEmpty() == true, "Expected errors but got: ${result.errors}")

        val hasOneofError = result.errors?.any { error ->
            error.message?.contains("Exactly one key must be specified for OneOf type") == true ||
                error.message?.contains("OneOf") == true
        } == true

        assertTrue(hasOneofError, "Expected oneof violation error but got: ${result.errors?.map { it.message }}")
    }
}
