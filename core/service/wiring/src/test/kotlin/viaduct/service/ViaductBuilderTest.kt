@file:Suppress("DEPRECATION", "TYPEALIAS_EXPANSION_DEPRECATION") // for imports of legacy bootstrap shim

package viaduct.service

import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.Coordinate
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flag
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.TenantAPIBootstrapper
import viaduct.service.api.spi.TenantAPIBootstrapperBuilder
import viaduct.service.api.spi.TenantModuleBootstrapper
import viaduct.service.runtime.SchemaConfiguration

class ViaductBuilderTest {
    val schema = mkSchema(
        """
             directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION | OBJECT
             directive @backingData(class: String!) on FIELD_DEFINITION

             type Query @scope(to: ["*"]) {
              _: String @deprecated
             }
             type Mutation @scope(to: ["*"]) {
               _: String @deprecated
             }

             directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION

             extend type Query @scope(to: ["publicScope"]) {
              helloWorld: String @resolver
             }
        """
    )

    val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    @Test
    fun testBuilderProxy() {
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .build().let {
                assertNotNull(it)
            }
    }

    @Test
    fun testWithMeterRegistry() {
        val meterRegistry = SimpleMeterRegistry()
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .withMeterRegistry(meterRegistry)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithResolverErrorReporter() {
        val errorReporter = ErrorReporter { _, _, _ ->
            // No-op for testing
        }
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .withResolverErrorReporter(errorReporter)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithDataFetcherErrorBuilder() {
        val errorBuilder = ResolverErrorBuilder.NOOP
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .withDataFetcherErrorBuilder(errorBuilder)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testMethodChaining() {
        val meterRegistry = SimpleMeterRegistry()
        val errorReporter = ErrorReporter.NOOP
        val errorBuilder = ResolverErrorBuilder.NOOP
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )

        val builder = ViaductBuilder()
        val result = builder
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .withMeterRegistry(meterRegistry)
            .withResolverErrorReporter(errorReporter)
            .withDataFetcherErrorBuilder(errorBuilder)
            .withProxyResolverFactory(ProxyResolverFactory.NO_OP)

        // Verify that method chaining returns the same builder instance
        assertSame(builder, result)

        val viaduct = result.build()
        assertNotNull(viaduct)
    }

    @Test
    fun testAllObservabilityMethodsTogether() {
        val meterRegistry = SimpleMeterRegistry()
        val errorReporter = ErrorReporter.NOOP
        val errorBuilder = ResolverErrorBuilder.NOOP
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )

        // Test that all observability methods can be used together
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .withMeterRegistry(meterRegistry)
            .withResolverErrorReporter(errorReporter)
            .withDataFetcherErrorBuilder(errorBuilder)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testObservabilityWithOtherBuilderMethods() {
        val meterRegistry = SimpleMeterRegistry()
        val errorReporter = ErrorReporter.NOOP
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )

        // Test that observability methods work with other builder methods
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withMeterRegistry(meterRegistry) // Observability before other methods
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withResolverErrorReporter(errorReporter) // Observability in the middle
            .withSchemaConfiguration(schemaConfiguration)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testBuilderReturnsCorrectInstance() {
        val meterRegistry = SimpleMeterRegistry()
        val builder = ViaductBuilder()
        SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )

        val returned = builder.withMeterRegistry(meterRegistry)

        // Verify that the method returns the same builder instance for chaining
        assertSame(builder, returned)
        assertEquals(builder, returned)
    }

    @Test
    fun `strict mode rejects missing resolver at build time`() {
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        val exception = assertThrows<GraphQLBuildError> {
            ViaductBuilder()
                .withFlagManager(flagManager)
                .withNoTenantAPIBootstrapper()
                .withSchemaConfiguration(schemaConfiguration)
                .build()
        }
        assertTrue(exception.message!!.contains("helloWorld"))
    }

    @Test
    fun testWithTenantModuleBootstrapper() {
        val bootstrapper = object : TenantModuleBootstrapper {
            override suspend fun bootstrap(
                tenantName: String,
                tenantBootstrapClass: Class<*>?
            ) = CodeInjector.Naive
        }
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withTenantModuleBootstrapper(bootstrapper)
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithGlobalIDCodec() {
        val codec = object : GlobalIDCodec {
            override fun serialize(
                typeName: String,
                localID: String
            ) = "$typeName:$localID"

            override fun deserialize(globalID: String): Pair<String, String> {
                val (t, id) = globalID.split(":", limit = 2)
                return Pair(t, id)
            }
        }
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .withGlobalIDCodec(codec)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithCheckerExecutorFactoryCreator() {
        val factory = object : CheckerExecutorFactory {
            override fun checkerExecutorForField(
                schema: ViaductSchema,
                typeName: String,
                fieldName: String
            ) = null

            override fun checkerExecutorForType(
                schema: ViaductSchema,
                typeName: String
            ) = null
        }
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .withCheckerExecutorFactoryCreator { factory }
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithTenantAPIBootstrapperBuilder() {
        val noOpBootstrapper = object : LegacyTenantModuleBootstrapper {
            override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> = emptyList()

            override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> = emptyList()
        }
        val bootstrapperBuilder = object : TenantAPIBootstrapperBuilder<LegacyTenantModuleBootstrapper> {
            override fun create(): TenantAPIBootstrapper<LegacyTenantModuleBootstrapper> =
                object : TenantAPIBootstrapper<LegacyTenantModuleBootstrapper> {
                    override suspend fun tenantModuleBootstrappers(): Iterable<LegacyTenantModuleBootstrapper> = listOf(noOpBootstrapper)
                }
        }
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withTenantAPIBootstrapperBuilder(bootstrapperBuilder)
            .withLenientResolverValidation()
            .withSchemaConfiguration(schemaConfiguration)
            .build()

        assertNotNull(viaduct)
    }

    private fun mkSchema(sdl: String): ViaductSchema = ViaductSchema(SchemaGenerator().makeExecutableSchema(SchemaParser().parse(sdl), RuntimeWiring.MOCKED_WIRING))
}
