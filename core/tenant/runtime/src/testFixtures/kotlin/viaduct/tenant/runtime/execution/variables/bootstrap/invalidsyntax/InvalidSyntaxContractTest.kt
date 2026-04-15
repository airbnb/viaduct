package viaduct.tenant.runtime.execution.variables.bootstrap.invalidsyntax

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.engine.api.GraphQLBuildError

@TestSchema(
    """
    extend type Query {
      fromArgumentField(arg: Int!): Int @resolver
      intermediary(arg: Int!): Int @resolver
      fromVariablesProvider: Int @resolver
    }
"""
)
abstract class InvalidSyntaxContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `invalid variables syntax fails at bootstrap time`() {
        var ex: Throwable? = null
        try {
            tryBuildViaductService()
        } catch (e: Exception) {
            ex = e.cause ?: e
        }

        requireNotNull(ex) { "Expected exception during tenant build" }

        assertTrue(ex is GraphQLBuildError, "Expected GraphQLBuildError but got ${ex::class.simpleName} with message ${ex.message}")
    }
}
