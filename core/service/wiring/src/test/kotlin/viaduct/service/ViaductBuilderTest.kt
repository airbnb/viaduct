package viaduct.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.service.api.ExecutionInput
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.DecodedGlobalID
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flag
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.NaiveTenantModuleInjectorFactory
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.TenantModuleInjectorFactory
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class ViaductBuilderTest {
    // Loaded from viaduct_builder_test_schema.graphqls on the test classpath. helloWorld has
    // no registered resolver, so strict validation rejects it unless withLenientResolverValidation() is set.
    val scopedSchemas = listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope")))

    val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    @Test
    fun testBuilderProxy() {
        ViaductBuilder()
            .withFlagManager(flagManager)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
            .build().let {
                assertNotNull(it)
            }
    }

    @Test
    fun `base SchemaScopeInfo executes across all scopes but hides tenantLocal fields`() {
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withLenientResolverValidation()
            .withScopedSchemas(listOf(SchemaScopeInfo.Base))
            .build()

        val visibleResult = viaduct.executeAsync(
            ExecutionInput.create("{ basePublicFieldA basePublicFieldB }"),
            SchemaId.Base,
        ).join()
        assertEquals(mapOf("basePublicFieldA" to null, "basePublicFieldB" to null), visibleResult.getData())
        assertTrue(visibleResult.errors.isEmpty())

        val tenantLocalResult = viaduct.executeAsync(
            ExecutionInput.create("{ baseTenantLocalField }"),
            SchemaId.Base,
        ).join()
        assertEquals(null, tenantLocalResult.getData())
        assertTrue(
            tenantLocalResult.errors.single().message.contains(
                "Field 'baseTenantLocalField' in type 'Query' is undefined"
            )
        )
    }

    // Bypasses ViaductBuilder: withScopedSchemas resolves schemas by scanning every .graphqls
    // file on the classpath, so an unscoped fixture can't coexist there with the @scope-bearing
    // ones used by other tests. An inline SDL string sidesteps the scan entirely.
    @Test
    @Suppress("DEPRECATION")
    fun `base SchemaScopeInfo executes the base view of an unscoped schema`() {
        val sdl = "extend type Query { unscopedVisibleField: String }"
        val schemaConfiguration = SchemaConfiguration.fromSdl(
            sdl,
            scopes = setOf(SchemaConfiguration.ScopeConfig.Base),
        )
        val viaduct = StandardViaduct.Builder()
            .withFlagManager(flagManager)
            .withSchemaConfiguration(schemaConfiguration)
            .build()

        val result = viaduct.executeAsync(
            ExecutionInput.create("{ unscopedVisibleField }"),
            SchemaId.Base,
        ).join()

        assertEquals(mapOf("unscopedVisibleField" to null), result.getData())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun testWithMeterRegistry() {
        val meterRegistry = SimpleMeterRegistry()
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
            .withMeterRegistry(meterRegistry)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithResolverErrorReporter() {
        val errorReporter = ErrorReporter { _, _, _ ->
            // No-op for testing
        }
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
            .withResolverErrorReporter(errorReporter)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithDataFetcherErrorBuilder() {
        val errorBuilder = ResolverErrorBuilder.NOOP
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
            .withDataFetcherErrorBuilder(errorBuilder)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testMethodChaining() {
        val meterRegistry = SimpleMeterRegistry()
        val errorReporter = ErrorReporter.NOOP
        val errorBuilder = ResolverErrorBuilder.NOOP

        val builder = ViaductBuilder()
        val result = builder
            .withFlagManager(flagManager)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
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

        // Test that all observability methods can be used together
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
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

        // Test that observability methods work with other builder methods
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withMeterRegistry(meterRegistry) // Observability before other methods
            .withLenientResolverValidation()
            .withResolverErrorReporter(errorReporter) // Observability in the middle
            .withScopedSchemas(scopedSchemas)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testBuilderReturnsCorrectInstance() {
        val meterRegistry = SimpleMeterRegistry()
        val builder = ViaductBuilder()

        val returned = builder.withMeterRegistry(meterRegistry)

        // Verify that the method returns the same builder instance for chaining
        assertSame(builder, returned)
        assertEquals(builder, returned)
    }

    @Test
    fun `strict mode rejects missing resolver at build time`() {
        val exception = assertThrows<GraphQLBuildError> {
            ViaductBuilder()
                .withFlagManager(flagManager)
                .withScopedSchemas(scopedSchemas)
                .build()
        }
        assertTrue(exception.message!!.contains("helloWorld"))
    }

    @Test
    fun testWithTenantModuleInjectorFactory() {
        val injectorFactory = object : TenantModuleInjectorFactory {
            override suspend fun bootstrap(
                tenantName: String,
                tenantBootstrapClass: Class<*>?
            ) = CodeInjector.Naive
        }
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withTenantModuleInjectorFactory(injectorFactory)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
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

            override fun deserialize(globalID: String): DecodedGlobalID {
                val (t, id) = globalID.split(":", limit = 2)
                return DecodedGlobalID(t, id)
            }
        }
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
            .withGlobalIDCodec(codec)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    @Suppress("DEPRECATION")
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
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
            .withCheckerExecutorFactoryCreator { factory }
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun `builds with an injector factory that discovers no tenant modules on this classpath`() {
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withTenantModuleInjectorFactory(NaiveTenantModuleInjectorFactory)
            .withLenientResolverValidation()
            .withScopedSchemas(scopedSchemas)
            .build()

        assertNotNull(viaduct)
    }
}
