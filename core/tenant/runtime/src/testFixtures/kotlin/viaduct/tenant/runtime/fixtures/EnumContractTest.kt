package viaduct.tenant.runtime.fixtures

import org.junit.jupiter.api.Test
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
abstract class EnumContractTest : FeatureAppTestBase() {
    init {
        sdl = """
            | #START_SCHEMA
            | enum Status {
            |   ACTIVE
            |   INACTIVE
            |   PENDING
            | }
            | extend type Query {
            |   "Return the Status.ACTIVE enum value"
            |   currentStatus: Status @resolver
            | }
            | #END_SCHEMA
        """.trimMargin()
    }

    @Test
    fun `statusResolverReturnsEnum`() {
        execute(query = "{ currentStatus }").assertEquals {
            "data" to {
                "currentStatus" to "ACTIVE"
            }
        }
    }
}
