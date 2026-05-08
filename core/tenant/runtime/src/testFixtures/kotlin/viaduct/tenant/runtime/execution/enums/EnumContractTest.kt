package viaduct.tenant.runtime.execution.enums

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.graphql.test.assertEquals

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
      "Return the requestContext cast to Status, or null if none"
      statusFromRequestContext: Status @resolver
    }
"""
)
abstract class EnumContractTest : KotlinFeatureAppTestContractBase() {
    @Test
    fun `statusResolverReturnsEnum`() {
        execute(query = "{ currentStatus }").assertEquals {
            "data" to {
                "currentStatus" to "ACTIVE"
            }
        }
    }

    @Test
    fun `requestContext is accessible in resolver`() {
        execute(query = "{ statusFromRequestContext }", requestContext = "INACTIVE").assertEquals {
            "data" to {
                "statusFromRequestContext" to "INACTIVE"
            }
        }
    }
}
