package viaduct.tenant.runtime.execution.scopes

import org.junit.jupiter.api.Test
import viaduct.api.testing.TestSchema
import viaduct.graphql.test.assertEquals
import viaduct.service.api.SchemaId
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.toScopeConfig
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for the @scope directive.
 *
 * Defines the SDL for scope-filtered schemas and tests that verify
 * scope-based field visibility. Concrete subclasses must provide
 * @Resolver inner classes for scope1Value and scope2Value.
 *
 * Note: This contract test is Kotlin-only because @scope and
 * withSchemaConfiguration() are not available in the Java Tenant API.
 */
@TestSchema(
    """
    type TestScope1Object @scope(to: ["SCOPE1"]) {
        strValue: String!
    }
    type TestScope2Object @scope(to: ["SCOPE2"]) {
      strValue: String!
    }

    extend type Query @scope(to: ["SCOPE1"]) {
      "Return TestScope1Object with a strValue string"
      scope1Value: TestScope1Object @resolver
    }

    extend type Query @scope(to: ["SCOPE2"]) {
      "Return TestScope2Object with a strValue string"
      scope2Value: TestScope2Object @resolver
    }
"""
)
abstract class ScopesContractTest : FeatureAppTestBase() {
    // -- Single-scope tests (only SCOPE1 registered) --

    @Test
    fun `Resolve query with SCOPE1 fields against SCHEMA_ID_1 schema succeeds`() {
        val schemaId = SchemaId.Scoped("SCHEMA_ID_1", setOf("SCOPE1"))
        withSchemaConfiguration(
            SchemaConfiguration.fromSdl(sdl, scopes = setOf(schemaId.toScopeConfig()))
        )
        execute(
            query = """
                query {
                    scope1Value {
                        strValue
                    }
                }
            """.trimIndent(),
            schemaId = schemaId
        ).assertEquals {
            "data" to {
                "scope1Value" to {
                    "strValue" to "scope 1 value"
                }
            }
        }
    }

    @Test
    fun `Resolve fails to run query with SCOPE2 fields against SCHEMA_ID_1 schema`() {
        val schemaId = SchemaId.Scoped("SCHEMA_ID_1", setOf("SCOPE1"))
        withSchemaConfiguration(
            SchemaConfiguration.fromSdl(sdl, scopes = setOf(schemaId.toScopeConfig()))
        )
        execute(
            query = """
                query {
                    scope2Value {
                        strValue
                    }
                }
            """.trimIndent(),
            schemaId = schemaId
        ).assertEquals {
            "errors" to arrayOf(
                {
                    "message" to "Validation error (FieldUndefined@[scope2Value]) : Field 'scope2Value' in type 'Query' is undefined"
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
    fun `Resolve query with SCOPE2 fields fails as SCHEMA_ID_2 is not registered`() {
        val schemaId = SchemaId.Scoped("SCHEMA_ID_1", setOf("SCOPE1"))
        withSchemaConfiguration(
            SchemaConfiguration.fromSdl(sdl, scopes = setOf(schemaId.toScopeConfig()))
        )
        execute(
            query = """
                query {
                    scope2Value {
                        strValue
                    }
                }
            """.trimIndent(),
            schemaId = SchemaId.None
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

    // -- Dual-scope tests (both SCOPE1 and SCOPE2 registered) --

    @Test
    fun `Resolve SCOPE1 fields against SCHEMA_ID_1 schema succeeds`() {
        val schemaId1 = SchemaId.Scoped("SCHEMA_ID_1", setOf("SCOPE1"))
        val schemaId2 = SchemaId.Scoped("SCHEMA_ID_2", setOf("SCOPE2"))
        withSchemaConfiguration(
            SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(schemaId1.toScopeConfig(), schemaId2.toScopeConfig())
            )
        )
        execute(
            query = """
                query {
                    scope1Value {
                        strValue
                    }
                },
            """.trimIndent(),
            schemaId = schemaId1,
        ).assertEquals {
            "data" to {
                "scope1Value" to {
                    "strValue" to "scope 1 value"
                }
            }
        }
    }

    @Test
    fun `Resolve SCOPE2 fields against SCHEMA_ID_2 schema succeeds`() {
        val schemaId1 = SchemaId.Scoped("SCHEMA_ID_1", setOf("SCOPE1"))
        val schemaId2 = SchemaId.Scoped("SCHEMA_ID_2", setOf("SCOPE2"))
        withSchemaConfiguration(
            SchemaConfiguration.fromSdl(
                sdl,
                scopes = setOf(schemaId1.toScopeConfig(), schemaId2.toScopeConfig())
            )
        )
        execute(
            query = """
                query {
                    scope2Value {
                        strValue
                    }
                }
            """.trimIndent(),
            schemaId = schemaId2,
        ).assertEquals {
            "data" to {
                "scope2Value" to {
                    "strValue" to "scope 2 value"
                }
            }
        }
    }
}
