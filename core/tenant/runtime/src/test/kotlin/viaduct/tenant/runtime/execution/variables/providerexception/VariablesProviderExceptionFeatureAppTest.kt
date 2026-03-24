@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.variables.providerexception

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import strikt.api.expectThat
import strikt.assertions.contains
import viaduct.api.Resolver
import viaduct.api.Variables
import viaduct.api.VariablesProvider
import viaduct.api.context.VariablesProviderContext
import viaduct.api.types.Arguments
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.execution.variables.providerexception.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Tests for VariablesProvider.provide throwing exceptions during query execution.
 * The exception should be caught and turned into a GraphQL field error,
 * while the rest of the query execution should be successful.
 */
class VariablesProviderExceptionFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
        | #START_SCHEMA
        | extend type Query {
        |   fromArgumentField(arg: Int!): Int @resolver
        |   intermediary(arg: Int!): Int @resolver
        |   fromVariablesProvider: Int @resolver
        |   workingField: String @resolver
        | }
        | #END_SCHEMA
        """.trimMargin()

    // VariablesProvider that throws an exception
    @Resolver(
        """
        fragment _ on Query {
            intermediary(arg: ${'$'}someVar)
        }
        """
    )
    class Query_FromVariablesProviderResolver : QueryResolvers.FromVariablesProvider() {
        override suspend fun resolve(ctx: Context): Int = ctx.objectValue.get("intermediary", Int::class)

        @Variables("someVar: Int!")
        class ThrowingVariablesProvider : VariablesProvider<Arguments> {
            override suspend fun provide(context: VariablesProviderContext<Arguments>): Map<String, Any?> {
                throw RuntimeException("Variables provider failed!")
            }
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

    @Resolver
    class Query_WorkingFieldResolver : QueryResolvers.WorkingField() {
        override suspend fun resolve(ctx: Context): String = "success"
    }

    @Test
    fun `variables provider exception becomes field error while rest of query succeeds`() {
        // Test working fields succeed
        val workingResult = execute(
            query = """
                query {
                    workingField
                    fromArgumentField(arg: 42)
                }
            """.trimIndent()
        )

        workingResult.assertEquals {
            "data" to {
                "workingField" to "success"
                "fromArgumentField" to 42
            }
        }

        // Test that the field with variables provider exception throws an exception during execution
        val result = execute(
            query = """
                query {
                    fromVariablesProvider
                }
            """.trimIndent()
        )

        // expect that result.errors is not empty and contains the expected error message
        assertTrue(result.errors.isNotEmpty(), "Expected errors but found none")
        expectThat(result.errors[0].message)
            .describedAs("Expected error message to contain 'Variables provider failed!'")
            .contains("Variables provider failed!")
    }
}
