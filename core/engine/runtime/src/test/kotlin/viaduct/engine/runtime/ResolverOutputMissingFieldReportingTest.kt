package viaduct.engine.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.EngineConfiguration
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.api.mocks.featureTestDefault
import viaduct.engine.api.mocks.runFeatureTest
import viaduct.engine.runtime.observability.ResolverOutputMissingFieldException
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.mocks.MockFlagManager

class ResolverOutputMissingFieldReportingTest {
    @Test
    fun `missing nullable selective resolver output is reported with Mat resolution`() {
        val reports = mutableListOf<ErrorReporter.Metadata>()
        val resultType = schema.schema.getObjectType("Result")
        val engineConfiguration = EngineConfiguration.featureTestDefault.copy(
            flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION),
            resolverErrorReporter = ErrorReporter { _, _, metadata -> reports += metadata },
        )

        EngineTestModule(schema) {
            field("Query" to "result") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                    ) { _, _, _, _, _ ->
                        ResolvedEngineObjectData.Builder(resultType).build()
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfiguration) {
            runQuery("{ result { value } }")
                .assertJson("""{ data: { result: { value: null } } }""")
        }

        assertEquals(1, reports.size)
        assertEquals("value", reports.single().fieldName)
        assertEquals("Result", reports.single().parentType)
    }

    @Test
    fun `explicit null selective resolver output is not reported with Mat resolution`() {
        val reports = mutableListOf<ErrorReporter.Metadata>()
        val resultType = schema.schema.getObjectType("Result")
        val engineConfiguration = EngineConfiguration.featureTestDefault.copy(
            flagManager = MockFlagManager.create(FlagManager.Flags.ENABLE_MAT_RESOLUTION),
            resolverErrorReporter = ErrorReporter { _, _, metadata -> reports += metadata },
        )

        EngineTestModule(schema) {
            field("Query" to "result") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                    ) { _, _, _, _, _ ->
                        ResolvedEngineObjectData.Builder(resultType)
                            .put("value", null)
                            .build()
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfiguration) {
            runQuery("{ result { value } }")
                .assertJson("""{ data: { result: { value: null } } }""")
        }

        assertTrue(reports.isEmpty())
    }

    @Test
    fun `missing nullable selective resolver output becomes a GraphQL error with Mat resolution`() {
        val reports = mutableListOf<ErrorReporter.Metadata>()
        val resultType = schema.schema.getObjectType("Result")
        val engineConfiguration = EngineConfiguration.featureTestDefault.copy(
            flagManager =
                MockFlagManager.create(
                    FlagManager.Flags.ENABLE_MAT_RESOLUTION,
                    FlagManager.Flags.ENABLE_RESOLVER_OUTPUT_MISSING_FIELD_ERRORS,
                ),
            resolverErrorReporter = ErrorReporter { _, _, metadata -> reports += metadata },
        )

        EngineTestModule(schema) {
            field("Query" to "result") {
                resolverExecutor {
                    MockFieldUnbatchedResolverExecutor(
                        isSelective = true,
                        resolverId = resolverId,
                    ) { _, _, _, _, _ ->
                        ResolvedEngineObjectData.Builder(resultType).build()
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfiguration) {
            val result = runQuery("{ result { value } }")

            assertEquals(
                mapOf("result" to mapOf("value" to null)),
                result.getData<Map<String, Any?>>(),
            )
            assertEquals(1, result.errors.size)
            assertEquals(listOf("result", "value"), result.errors.single().path)
            assertEquals(
                "Resolver output did not contain requested field `Result.value`",
                result.errors.single().message,
            )
            assertEquals(
                ResolverOutputMissingFieldException.GRAPHQL_ERROR_CODE,
                result.errors.single().extensions["code"],
            )
        }

        assertEquals(1, reports.size)
    }

    @Test
    fun `missing nullable resolver output is reported without a GraphQL error`() {
        val reports = mutableListOf<ErrorReporter.Metadata>()
        val resultType = schema.schema.getObjectType("Result")
        val engineConfiguration = EngineConfiguration.featureTestDefault.copy(
            flagManager = MockFlagManager.Disabled,
            resolverErrorReporter = ErrorReporter { _, _, metadata -> reports += metadata }
        )

        EngineTestModule(schema) {
            field("Query" to "result") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ResolvedEngineObjectData.Builder(resultType).build()
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfiguration) {
            runQuery("{ result { value } }")
                .assertJson("""{ data: { result: { value: null } } }""")
        }

        assertEquals(1, reports.size)
        assertEquals("value", reports.single().fieldName)
        assertEquals("Result", reports.single().parentType)
        assertEquals(null, reports.single().resolvers)
        assertNotNull(reports.single().requestContext)
    }

    @Test
    fun `missing nullable resolver output becomes an OER field error when enabled`() {
        val reports = mutableListOf<ErrorReporter.Metadata>()
        val resultType = schema.schema.getObjectType("Result")
        val engineConfiguration = EngineConfiguration.featureTestDefault.copy(
            flagManager =
                MockFlagManager.create(
                    FlagManager.Flags.ENABLE_RESOLVER_OUTPUT_MISSING_FIELD_ERRORS
                ),
            resolverErrorReporter = ErrorReporter { _, _, metadata -> reports += metadata },
        )

        EngineTestModule(schema) {
            field("Query" to "result") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ResolvedEngineObjectData.Builder(resultType).build()
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfiguration) {
            val result = runQuery("{ result { value } }")

            assertEquals(
                mapOf("result" to mapOf("value" to null)),
                result.getData<Map<String, Any?>>(),
            )
            assertEquals(1, result.errors.size)
            assertEquals(listOf("result", "value"), result.errors.single().path)
            assertEquals(
                "Resolver output did not contain requested field `Result.value`",
                result.errors.single().message,
            )
            assertEquals(
                ResolverOutputMissingFieldException.GRAPHQL_ERROR_CODE,
                result.errors.single().extensions["code"],
            )
        }

        assertEquals(1, reports.size)
        assertEquals("value", reports.single().fieldName)
        assertEquals(null, reports.single().resolvers)
    }

    @Test
    fun `explicit null resolver output is not reported`() {
        val reports = mutableListOf<ErrorReporter.Metadata>()
        val resultType = schema.schema.getObjectType("Result")
        val engineConfiguration = EngineConfiguration.featureTestDefault.copy(
            flagManager =
                MockFlagManager.create(
                    FlagManager.Flags.ENABLE_RESOLVER_OUTPUT_MISSING_FIELD_ERRORS
                ),
            resolverErrorReporter = ErrorReporter { _, _, metadata -> reports += metadata }
        )

        EngineTestModule(schema) {
            field("Query" to "result") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ResolvedEngineObjectData.Builder(resultType)
                            .put("value", null)
                            .build()
                    }
                }
            }
        }.runFeatureTest(engineConfig = engineConfiguration) {
            runQuery("{ result { value } }")
                .assertJson("""{ data: { result: { value: null } } }""")
        }

        assertTrue(reports.isEmpty())
    }

    @Test
    fun `field owned by another resolver is not reported as missing from parent output`() {
        val reports = mutableListOf<ErrorReporter.Metadata>()
        val resultType = schema.schema.getObjectType("Result")
        val engineConfiguration = EngineConfiguration.featureTestDefault.copy(
            flagManager = MockFlagManager.Disabled,
            resolverErrorReporter = ErrorReporter { _, _, metadata -> reports += metadata }
        )

        EngineTestModule(schema) {
            field("Query" to "result") {
                resolver {
                    fn { _, _, _, _, _ ->
                        ResolvedEngineObjectData.Builder(resultType).build()
                    }
                }
            }
            field("Result" to "value") {
                resolver {
                    fn { _, _, _, _, _ -> "resolved by field resolver" }
                }
            }
        }.runFeatureTest(engineConfig = engineConfiguration) {
            runQuery("{ result { value } }")
                .assertJson("""{ data: { result: { value: "resolved by field resolver" } } }""")
        }

        assertTrue(reports.isEmpty())
    }

    private companion object {
        val schema = createSchemaWithWiring(
            """
            extend type Query {
                result: Result
            }

            type Result {
                value: String
            }
            """.trimIndent()
        )
    }
}
