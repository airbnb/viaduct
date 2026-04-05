package viaduct.tenant.runtime.execution.enums

import org.junit.jupiter.api.Test
import viaduct.graphql.test.assertEquals
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.runtime.fixtures.TestSchema

/**
 * Contract test for enum type resolution patterns.
 *
 * Defines the SDL and assertions for:
 * - Enum types with multiple values
 * - Resolvers that return enum values
 *
 * Extend this class and provide resolver implementations to verify that a given
 * runtime correctly supports these patterns.
 */
@TestSchema(
    """
    enum Status {
      ACTIVE
      INACTIVE
      PENDING
    }
    extend type Query {
      "Return the Status.ACTIVE enum value"
      currentStatus: Status @resolver
    }
"""
)
abstract class EnumContractTest : FeatureAppTestBase() {
    @Test
    fun `statusResolverReturnsEnum`() {
        execute(query = "{ currentStatus }").assertEquals {
            "data" to {
                "currentStatus" to "ACTIVE"
            }
        }
    }
}
