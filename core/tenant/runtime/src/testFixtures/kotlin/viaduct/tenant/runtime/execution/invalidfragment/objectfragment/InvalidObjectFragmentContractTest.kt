package viaduct.tenant.runtime.execution.invalidfragment.objectfragment

import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.engine.api.GraphQLBuildError
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test that verifies an invalid object value fragment does not cause
 * a Guice exception, but instead produces a [GraphQLBuildError].
 *
 * Extend this class and provide resolver implementations with an invalid
 * object fragment annotation.
 */
@TestSchema(
    """
    type Foo {
      bar: String @resolver
      baz: String @resolver
    }
    extend type Query {
      greeting: Foo @resolver
    }
    """
)
abstract class InvalidObjectFragmentContractTest : FeatureAppTestBase() {
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
