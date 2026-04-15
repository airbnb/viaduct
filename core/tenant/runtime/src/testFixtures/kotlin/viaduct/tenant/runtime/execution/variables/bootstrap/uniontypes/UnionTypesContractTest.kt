package viaduct.tenant.runtime.execution.variables.bootstrap.uniontypes

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.engine.api.GraphQLBuildError

@TestSchema(
    """
    union SearchResult = Book | Author
    type Book {
      title: String
    }
    type Author {
      name: String
    }
    extend type Query {
      fromArgumentField(arg: Int!): Int @resolver
      intermediary(arg: Int!): Int @resolver
      fromVariablesProvider: Int @resolver
    }
"""
)
abstract class UnionTypesContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    @Disabled("https://app.asana.com/1/150975571430/project/1207604899751448/task/1210664713712227")
    fun `union type in variables fails at bootstrap time`() {
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
