@file:Suppress("unused", "ClassName")

package viaduct.tenant.runtime.execution.scopes

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.api.Resolver
import viaduct.graphql.test.assertEquals
import viaduct.service.api.SchemaId
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.toScopeConfig
import viaduct.tenant.runtime.execution.scopes.resolverbases.QueryResolvers
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

class ScopesFeatureAppTest : FeatureAppTestBase() {
    override var sdl =
        """
     | #START_SCHEMA
     |   type TestScope1Object @scope(to: ["SCOPE1"]) {
     |       strValue: String!
     |   }
     |   type TestScope2Object @scope(to: ["SCOPE2"]) {
     |     strValue: String!
     |   }
     |
     |   extend type Query @scope(to: ["SCOPE1"]) {
     |     scope1Value: TestScope1Object @resolver
     |   }
     |
     |   extend type Query @scope(to: ["SCOPE2"]) {
     |     scope2Value: TestScope2Object @resolver
     |   }
     | #END_SCHEMA
        """.trimMargin()

    @Resolver
    class Scope1ValueResolver : QueryResolvers.Scope1Value() {
        override suspend fun resolve(ctx: Context): TestScope1Object {
            return TestScope1Object.Builder(ctx)
                .strValue("scope 1 value")
                .build()
        }
    }

    @Resolver
    class Scope2ValueResolver : QueryResolvers.Scope2Value() {
        override suspend fun resolve(ctx: Context): TestScope2Object {
            return TestScope2Object.Builder(ctx)
                .strValue("scope 2 value")
                .build()
        }
    }

    @Nested
    @DisplayName("Scopes 1 tests")
    inner class Scopes1Test {
        private val schemaId = SchemaId.Scoped("SCHEMA_ID_1", setOf("SCOPE1"))

        @BeforeEach
        fun setup() {
            withSchemaConfiguration(
                SchemaConfiguration
                    .fromSdl(
                        sdl,
                        scopes = setOf(schemaId.toScopeConfig())
                    )
            )
        }

        @Test
        fun `Resolve query with SCOPE1 fields against SCHEMA_ID_1 schema succeeds`() {
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
    }

    @Nested
    @DisplayName("Scopes 2 tests")
    inner class Scopes2Test {
        private val schemaId1 = SchemaId.Scoped("SCHEMA_ID_1", setOf("SCOPE1"))
        private val schemaId2 = SchemaId.Scoped("SCHEMA_ID_2", setOf("SCOPE2"))

        @BeforeEach
        fun setup() {
            withSchemaConfiguration(
                SchemaConfiguration.fromSdl(
                    sdl,
                    scopes = setOf(
                        schemaId1.toScopeConfig(),
                        schemaId2.toScopeConfig(),
                    )
                )
            )
        }

        @Test
        fun `Resolve SCOPE1 fields against SCHEMA_ID_1 schema succeeds`() {
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
}
