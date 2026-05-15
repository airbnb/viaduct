@file:OptIn(InternalApi::class)

package viaduct.service.runtime

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.every
import io.mockk.mockk
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import viaduct.apiannotations.InternalApi
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.SchemaId

/**
 * NoScopesMode runtime verification.
 */
class NoScopesModeVerificationTest {

    companion object {
        private const val SIMPLE_SDL = """
            type Query {
                hello: String
            }
        """

        fun createSchemaFromSdl(sdl: String = SIMPLE_SDL): ViaductSchema =
            ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl)))

        fun createSchemaFactory(): SchemaFactory =
            mockk<SchemaFactory>().also { sf ->
                every { sf.fromSdl(any()) } answers { createSchemaFromSdl(firstArg()) }
                every { sf.fromResources(any(), any()) } answers { createSchemaFromSdl() }
            }

        fun createDocumentProviderFactory() = mockk<DocumentProviderFactory>(relaxed = true)

        fun emptyClassLoader(): ClassLoader =
            URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())
    }

    // when META-INF/viaduct/schema-scoping.json is absent and only "FULL" is requested,
    // fromResources returns a NoScopesMode config:
    //   - scopedSchemas is empty
    //   - resolveSchemaId("FULL") returns emptySet()
    //   - resolveSchemaId(anything else) throws ViaductInvalidConfigurationException
    @Test
    fun `fromResources FULL-only with absent META-INF returns empty scopedSchemas and correct resolveSchemaId`() {
        val config = SchemaConfiguration.fromResources(setOf("FULL"), emptyClassLoader())

        assertTrue(config.scopedSchemas.isEmpty(), "NoScopesMode scopedSchemas must be empty")
        assertEquals(
            emptySet<String>(),
            config.resolveSchemaId("FULL"),
            "FULL must resolve to empty scope set"
        )
        assertThrows<ViaductInvalidConfigurationException>("non-FULL id must throw") {
            config.resolveSchemaId("nonExistentId")
        }
    }

    // fromResources(setOf("FULL")) with absent resource file returns a config
    // with a non-null FromResources fullSchemaConfig and empty scopedSchemas.
    @Test
    fun `fromResources FULL-only returns FromResources fullSchemaConfig with no scoped schemas`() {
        val config = SchemaConfiguration.fromResources(setOf("FULL"), emptyClassLoader())

        assertNotNull(config.fullSchemaConfig, "fullSchemaConfig must be non-null in NoScopesMode")
        assertTrue(
            config.scopedSchemas.isEmpty(),
            "zero scoped schemas in NoScopesMode"
        )
        assertInstanceOf(
            SchemaConfiguration.FullSchemaConfig.FromResources::class.java,
            config.fullSchemaConfig,
            "fullSchemaConfig must be the FromResources variant"
        )
    }

    // at EngineRegistry startup in NoScopesMode, no log messages containing
    // "scope" (case-insensitive) or "materialization_time_ms" are emitted at any level.
    @Test
    fun `NoScopesMode EngineRegistry startup emits no scope-related log messages`() {
        val logger = LoggerFactory.getLogger(EngineRegistry::class.java) as Logger
        val originalLevel = logger.level
        logger.level = Level.DEBUG  // capture DEBUG-level materialization_time_ms messages too
        val listAppender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(listAppender)
        try {
            val config = SchemaConfiguration.fromResources(setOf("FULL"), emptyClassLoader())
            val factory = EngineRegistry.Factory(createSchemaFactory(), createDocumentProviderFactory())
            factory.create(config)

            val scopeRelatedMessages = listAppender.list
                .map { it.formattedMessage }
                .filter { msg ->
                    msg.contains("scope", ignoreCase = true) ||
                        msg.contains("materialization_time_ms")
                }
            assertTrue(
                scopeRelatedMessages.isEmpty(),
                "no scope-related log messages in NoScopesMode. Found: $scopeRelatedMessages"
            )
        } finally {
            logger.detachAppender(listAppender)
            logger.level = originalLevel
        }
    }

    // the no-arg fromResources() factory produces a config with a FromResources
    // fullSchemaConfig and no scoped schemas (backward-compatible baseline path).
    @Test
    fun `no-arg fromResources produces FromResources fullSchemaConfig with empty scopedSchemas`() {
        val config = SchemaConfiguration.fromResources()

        assertTrue(
            config.scopedSchemas.isEmpty(),
            "no-arg fromResources() must produce zero scoped schemas"
        )
        assertNotNull(config.fullSchemaConfig, "fullSchemaConfig must be non-null")
        assertInstanceOf(
            SchemaConfiguration.FullSchemaConfig.FromResources::class.java,
            config.fullSchemaConfig,
            "no-arg fromResources() must use the FromResources config variant"
        )
    }

    // SchemaFactory.fromResources path is identical to the backward-compatible baseline.
    // The schema returned by EngineRegistry.getSchema(FULL) is the exact same instance
    // that SchemaFactory.fromResources returned — proving no intermediate transformation.
    @Test
    fun `EngineRegistry full schema from no-arg fromResources is identical to SchemaFactory output`() {
        val expectedSchema = createSchemaFromSdl()
        val schemaFactory = mockk<SchemaFactory>()
        every { schemaFactory.fromResources(any(), any()) } returns expectedSchema

        val config = SchemaConfiguration.fromResources()
        val factory = EngineRegistry.Factory(schemaFactory, createDocumentProviderFactory())
        val registry = factory.create(config)

        val fullSchema = registry.getSchema(SchemaId.Full)
        assertSame(
            expectedSchema,
            fullSchema,
            "registry full schema must be the exact instance returned by SchemaFactory.fromResources"
        )
    }

    // backward compatibility — the no-arg fromResources() path builds an EngineRegistry
    // with exactly one registered schema (FULL) and no errors.
    @Test
    fun `backward compat no-arg fromResources registry registers only FULL schema`() {
        val config = SchemaConfiguration.fromResources()
        val factory = EngineRegistry.Factory(createSchemaFactory(), createDocumentProviderFactory())
        val registry = factory.create(config)

        val ids = registry.getRegisteredSchemaIds()
        assertEquals(1, ids.size, "only FULL schema registered in NoScopesMode")
        assertTrue(ids.contains(SchemaId.Full), "SchemaId.Full must be in the registry")
    }
}
