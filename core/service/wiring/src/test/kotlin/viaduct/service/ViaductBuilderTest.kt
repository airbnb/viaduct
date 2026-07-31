package viaduct.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.Coordinate
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.CheckerExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.ProxyResolverFactory
import viaduct.engine.api.spi.TenantAPIBootstrapper
import viaduct.engine.api.spi.TenantAPIBootstrapperBuilder
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.service.api.ExecutionInput
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.DecodedGlobalID
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flag
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.TenantModuleInjectorFactory

class ViaductBuilderTest {
    // fromSdl parses this raw SDL and the framework injects the @scope/@resolver directive
    // definitions, so the SDL must not redeclare them (redeclaring a core directive fails the
    // schema build). helloWorld has no registered resolver, so strict validation rejects it
    // unless withLenientResolverValidation() is set.
    val sdl =
        """
             extend type Query @scope(to: ["publicScope"]) {
              helloWorld: String @resolver
             }
        """

    val flagManager = object : FlagManager {
        override fun isEnabled(flag: Flag) = true
    }

    @Test
    fun testBuilderProxy() {
        ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
            .build().let {
                assertNotNull(it)
            }
    }

    @Test
    fun testWithScopedSchemasFromSdl() {
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun `base SchemaScopeInfo executes the base view of a scope-aware schema end to end`() {
        val schemaInfo = SchemaScopeInfo.Base
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withScopedSchemasFromSdl(
                """
                extend type Query @scope(to: ["public"]) {
                    publicField: String
                    internalOnly: String @tenantLocal
                }

                extend type Query @scope(to: ["private"]) {
                    privateField: String
                }
                """.trimIndent(),
                listOf(schemaInfo),
            )
            .build()

        val visibleResult = viaduct.executeAsync(
            ExecutionInput.create("{ publicField privateField }"),
            schemaInfo.schemaId,
        ).join()
        assertEquals(mapOf("publicField" to null, "privateField" to null), visibleResult.getData())
        assertTrue(visibleResult.errors.isEmpty())

        val tenantLocalResult = viaduct.executeAsync(
            ExecutionInput.create("{ internalOnly }"),
            schemaInfo.schemaId,
        ).join()
        assertNull(tenantLocalResult.getData())
        assertTrue(
            tenantLocalResult.errors.single().message.contains(
                "Field 'internalOnly' in type 'Query' is undefined"
            )
        )
    }

    @Test
    fun `schema without scopes executes the canonical base without SchemaScopeInfo`() {
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withScopedSchemasFromSdl(
                "extend type Query { visible: String }",
                emptyList(),
            )
            .build()

        val result = viaduct.executeAsync(ExecutionInput.create("{ visible }")).join()

        assertEquals(mapOf("visible" to null), result.getData())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `scoped SchemaScopeInfo executes only its selected scope end to end`() {
        val schemaInfo = SchemaScopeInfo.Scoped("public", setOf("public"))
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withScopedSchemasFromSdl(
                """
                extend type Query @scope(to: ["public"]) {
                    publicField: String
                }

                extend type Query @scope(to: ["private"]) {
                    privateField: String
                }
                """.trimIndent(),
                listOf(schemaInfo),
            )
            .build()

        val publicResult = viaduct.executeAsync(
            ExecutionInput.create("{ publicField }"),
            schemaInfo.schemaId,
        ).join()
        assertEquals(mapOf("publicField" to null), publicResult.getData())
        assertTrue(publicResult.errors.isEmpty())

        val privateResult = viaduct.executeAsync(
            ExecutionInput.create("{ privateField }"),
            schemaInfo.schemaId,
        ).join()
        assertNull(privateResult.getData())
        assertTrue(
            privateResult.errors.single().message.contains(
                "Field 'privateField' in type 'Query' is undefined"
            )
        )
    }

    @Test
    fun testWithMeterRegistry() {
        val meterRegistry = SimpleMeterRegistry()
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
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
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
            .withResolverErrorReporter(errorReporter)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithDataFetcherErrorBuilder() {
        val errorBuilder = ResolverErrorBuilder.NOOP
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
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
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
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
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
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
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withResolverErrorReporter(errorReporter) // Observability in the middle
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
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
                .withNoTenantAPIBootstrapper()
                .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
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
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
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
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
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
            .withNoTenantAPIBootstrapper()
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
            .withCheckerExecutorFactoryCreator { factory }
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithTenantAPIBootstrapperBuilder() {
        val noOpBootstrapper = object : TenantModuleBootstrapper {
            override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> = emptyList()

            override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> = emptyList()
        }
        val bootstrapperBuilder = object : TenantAPIBootstrapperBuilder {
            override fun create(): TenantAPIBootstrapper =
                object : TenantAPIBootstrapper {
                    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> = listOf(noOpBootstrapper)
                }
        }
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withTenantAPIBootstrapperBuilder(bootstrapperBuilder)
            .withLenientResolverValidation()
            .withScopedSchemasFromSdl(sdl, listOf(SchemaScopeInfo.Scoped("public", setOf("publicScope"))))
            .build()

        assertNotNull(viaduct)
    }
}
