@file:Suppress("unused", "ClassName")

package viaduct.tenant.tutorial06

import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.service.api.SchemaId
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.toScopeConfig
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase
import viaduct.tenant.tutorial06.resolverbases.QueryResolvers

/**
 * LEARNING OBJECTIVES:
 * - Implement API security through field-level access control
 * - Deploy different API versions for different client types
 * - Organize GraphQL schemas by scope (USER, ADMIN, INTERNAL)
 * - Prevent accidental data exposure between client applications
 *
 * VIADUCT FEATURES DEMONSTRATED:
 * - @scope directive for field and type access control
 * - Multiple API deployments from single schema
 * - Scoped schema registration with SchemaRegistryBuilder
 * - Automatic field filtering based on scope permissions
 *
 * CONCEPTS COVERED:
 * - Multi-tenant API architecture
 * - Security through schema scope separation
 * - Customer-facing vs admin vs internal API variants
 * - Schema validation errors for unauthorized fields
 *
 * PREVIOUS: [viaduct.tenant.tutorial05.SimpleMutationsFeatureAppTest]
 * NEXT: [viaduct.tenant.tutorial07.SimpleBatchResolverFeatureAppTest]
 */
class SimpleScopesFeatureAppTest : FeatureAppTestBase() {
    override var sdl = """
        | #START_SCHEMA
        | extend type Query @scope(to: ["USER"]) {          # Only USER scope can access
        |   myOrders(userId: String!): [String!]! @resolver
        | }
        |
        | extend type Query @scope(to: ["ADMIN"]) {         # Only ADMIN scope can access
        |   allUserData: [String!]! @resolver
        | }
        | #END_SCHEMA
    """.trimMargin()

    /**
     * USER-SCOPED RESOLVER - Customer-facing functionality
     *
     * What YOU write:
     * - Business logic for user-specific operations
     * - Input validation and security checks
     * - Data filtering appropriate for customer APIs
     *
     * What VIADUCT handles:
     * - Only generates this resolver in APIs registered with USER scope
     * - Automatic field exclusion in non-USER deployments
     * - Schema validation prevents unauthorized access
     */
    @Resolver
    class MyOrdersResolver : QueryResolvers.MyOrders() { // Generated from USER-scoped field
        override suspend fun resolve(ctx: Context): List<String> {
            // SECURITY CHECK - validate userId against auth context
            val userId = ctx.arguments.userId

            // In production: ctx.authContext.validateUserAccess(userId)

            // CUSTOMER DATA - safe for external APIs
            val userOrders = when (userId) {
                "user-123" -> listOf("Order #1001", "Order #1002")
                "user-456" -> listOf("Order #2001")
                else -> emptyList()
            }

            return userOrders
        }
    }

    /**
     * ADMIN-SCOPED RESOLVER - Internal dashboard functionality
     *
     * Critical: This contains SENSITIVE DATA that should never
     * be exposed to customer-facing applications
     */
    @Resolver
    class AllUserDataResolver : QueryResolvers.AllUserData() { // Generated from ADMIN-scoped field
        override suspend fun resolve(ctx: Context): List<String> {
            // SENSITIVE INTERNAL DATA
            // In production: would query all users with PII, financial data, etc.
            return listOf("User: john@example.com", "User: jane@example.com")
        }
    }

    @Test
    fun `Customer app can access user orders but not admin data`() {
        // `withSchemaConfiguration` lets you pass in a schema config
        // to configure the [Viaduct] object being tested.  In this example we're creating a
        // scoped schema named "CUSTOMER_API".  See Viaduct user docs for more on
        // schema scoping.
        val schemaId = SchemaId.Scoped("CUSTOMER_API", setOf("USER"))
        withSchemaConfiguration(
            SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(schemaId.toScopeConfig()),
            )
        )

        // USER SCOPE ACCESS - Works
        execute(
            query = """
                query {
                    myOrders(userId: "user-123")
                }
            """.trimIndent(),
            schemaId = schemaId // Using customer deployment
        ).assertEquals {
            "data" to {
                "myOrders" to listOf("Order #1001", "Order #1002")
            }
        }

        // ADMIN SCOPE BLOCKED - Security protection
        execute(
            query = """
                query {
                    allUserData  # This field doesn't exist in customer API
                }
            """.trimIndent(),
            schemaId = schemaId
        ).assertEquals {
            "errors" to arrayOf(
                {
                    "message" to "Validation error (FieldUndefined@[allUserData]) : Field 'allUserData' in type 'Query' is undefined"
                    "locations" to arrayOf(
                        {
                            "line" to 2
                            "column" to 5
                        }
                    )
                    "extensions" to {
                        "classification" to "ValidationError"
                    }
                }
            )
            "data" to null
        }
    }

    @Test
    fun `Admin dashboard can access admin data but not user-specific data`() {
        // Register admin dashboard schema with ADMIN scope only
        // This simulates deploying the API for internal admin tools and dashboards

        val schemaId = SchemaId.Scoped("ADMIN_API", setOf("ADMIN"))
        withSchemaConfiguration(
            SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(schemaId.toScopeConfig())
            )
        )

        // ADMIN SCOPE ACCESS - Works
        execute(
            query = """
                query {
                    allUserData
                }
            """.trimIndent(),
            schemaId = schemaId
        ).assertEquals {
            "data" to {
                "allUserData" to listOf("User: john@example.com", "User: jane@example.com")
            }
        }

        // USER SCOPE BLOCKED - Keeps admin tools focused
        execute(
            query = """
                query {
                    myOrders(userId: "user-123")  # This field doesn't exist in admin API
                }
            """.trimIndent(),
            schemaId = schemaId
        ).assertEquals {
            "errors" to arrayOf(
                {
                    "message" to "Validation error (FieldUndefined@[myOrders]) : Field 'myOrders' in type 'Query' is undefined"
                    "locations" to arrayOf(
                        {
                            "line" to 2
                            "column" to 5
                        }
                    )
                    "extensions" to {
                        "classification" to "ValidationError"
                    }
                }
            )
            "data" to null
        }
    }

    @Test
    fun `Internal tools with both scopes can access everything`() {
        // Register internal tools schema with both USER and ADMIN scopes
        // This simulates deploying the API for support tools that need access to everything
        val schemaId = SchemaId.Scoped("INTERNAL_API", setOf("USER", "ADMIN"))
        withSchemaConfiguration(
            SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(schemaId.toScopeConfig())
            )
        )

        // COMBINED ACCESS - Both scopes available
        execute(
            query = """
                query {
                    myOrders(userId: "user-456")    # From USER scope
                    allUserData                     # From ADMIN scope
                }
            """.trimIndent(),
            schemaId = schemaId
        ).assertEquals {
            "data" to {
                "myOrders" to listOf("Order #2001")
                "allUserData" to listOf("User: john@example.com", "User: jane@example.com")
            }
        }
    }

    @Test
    fun `Unknown API deployment fails`() {
        // SECURITY TEST - unregistered API endpoints should fail
        execute(
            query = """
                query {
                    myOrders(userId: "user-123")
                }
            """.trimIndent(),
            schemaId = SchemaId.None // Never registered
        ).assertEquals {
            "errors" to arrayOf(
                {
                    "message" to "Schema not found for schemaId=SchemaId(id='NONE')"
                    "locations" to emptyList<String>()
                    "extensions" to {
                        "classification" to "DataFetchingException"
                    }
                }
            )
        }
    }

    /**
     * REAL-WORLD DEPLOYMENT SCENARIOS:
     *
     * Customer Mobile App:
     * - Scope: ["USER"]
     * - Access: myOrders, userProfile, customerSupport
     * - Blocked: adminData, internalMetrics, allUsers
     *
     * Admin Dashboard:
     * - Scope: ["ADMIN"]
     * - Access: allUserData, systemMetrics, adminReports
     * - Blocked: customerSpecific operations (prevents admin from impersonating users)
     *
     * Customer Support Tool:
     * - Scope: ["USER", "ADMIN"]
     * - Access: Everything (needed for support scenarios)
     * - Use case: Support agents helping customers
     *
     * KEY BENEFITS:
     * - Same codebase -> multiple secure API deployments
     * - Impossible to accidentally expose admin data to customers
     * - Fine-grained access control at field level
     * - Clear separation of concerns by client type
     * - Easier compliance and security auditing
     */
}
