package viaduct.service

import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.FlagManager.Flag
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.runtime.SchemaConfiguration

class ViaductBuilderTest {
    val schema = mkSchema(
        """
             directive @resolver on FIELD_DEFINITION | OBJECT
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
            .withSchemaConfiguration(schemaConfiguration)
            .withDataFetcherErrorBuilder(errorBuilder)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testWithDataFetcherExceptionHandler() {
        val exceptionHandler = object : DataFetcherExceptionHandler {
            override fun handleException(handlerParameters: DataFetcherExceptionHandlerParameters): CompletableFuture<DataFetcherExceptionHandlerResult> {
                return CompletableFuture.completedFuture(
                    DataFetcherExceptionHandlerResult.newResult().build()
                )
            }
        }
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withSchemaConfiguration(schemaConfiguration)
            .withDataFetcherExceptionHandler(exceptionHandler)
            .build()

        assertNotNull(viaduct)
    }

    @Test
    fun testMethodChaining() {
        val meterRegistry = SimpleMeterRegistry()
        val errorReporter = ErrorReporter.NOOP
        val errorBuilder = ResolverErrorBuilder.NOOP
        val exceptionHandler = object : DataFetcherExceptionHandler {
            override fun handleException(handlerParameters: DataFetcherExceptionHandlerParameters): CompletableFuture<DataFetcherExceptionHandlerResult> {
                return CompletableFuture.completedFuture(
                    DataFetcherExceptionHandlerResult.newResult().build()
                )
            }
        }
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )

        val builder = ViaductBuilder()
        val result = builder
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withSchemaConfiguration(schemaConfiguration)
            .withMeterRegistry(meterRegistry)
            .withResolverErrorReporter(errorReporter)
            .withDataFetcherErrorBuilder(errorBuilder)
            .withDataFetcherExceptionHandler(exceptionHandler)

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
        val exceptionHandler = object : DataFetcherExceptionHandler {
            override fun handleException(handlerParameters: DataFetcherExceptionHandlerParameters): CompletableFuture<DataFetcherExceptionHandlerResult> {
                return CompletableFuture.completedFuture(
                    DataFetcherExceptionHandlerResult.newResult().build()
                )
            }
        }
        val schemaConfiguration = SchemaConfiguration.fromSchema(
            schema,
            scopes = setOf(SchemaConfiguration.ScopeConfig("public", setOf("publicScope")))
        )

        // Test that all observability methods can be used together
        val viaduct = ViaductBuilder()
            .withFlagManager(flagManager)
            .withNoTenantAPIBootstrapper()
            .withSchemaConfiguration(schemaConfiguration)
            .withMeterRegistry(meterRegistry)
            .withResolverErrorReporter(errorReporter)
            .withDataFetcherErrorBuilder(errorBuilder)
            .withDataFetcherExceptionHandler(exceptionHandler)
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

    private fun mkSchema(sdl: String): ViaductSchema = ViaductSchema(SchemaGenerator().makeExecutableSchema(SchemaParser().parse(sdl), RuntimeWiring.MOCKED_WIRING))
}
