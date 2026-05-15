@file:OptIn(InternalApi::class)

package viaduct.service.runtime

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.every
import io.mockk.mockk
import java.net.URLClassLoader
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.apiannotations.InternalApi
import viaduct.engine.SchemaFactory
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.SchemaId

/**
 * NoScopesMode control-flow verification.
 * The scoped-mode execution path (ScopedSchemaConfig.build) is NEVER entered in
 * NoScopesMode. Two assertions together prove this:
 *   (a) config.scopedSchemas is empty before registry construction — no build() targets exist.
 *   (b) registry.getRegisteredSchemaIds() == {SchemaId.Full} after construction — no scoped
 *       schemas were materialized, so the scoped path was never entered.
 * Positive control: with scoped schemas declared, the registry contains both FULL and scoped
 * schemas, confirming the scoped-mode path IS entered when schemas are configured.
 */
class NoScopesModeControlFlowTest {

    companion object {
        private const val SIMPLE_SDL = """
            type Query {
                hello: String
            }
        """

        private const val SCOPED_SDL = """
            schema { query: Query }

            directive @scope(to: [String!]!) repeatable on OBJECT | INPUT_OBJECT | ENUM | INTERFACE | UNION | SCALAR

            type Query @scope(to: ["*"]) {
                hello: String
            }

            type AdminType @scope(to: ["admin"]) { id: ID! }
            type PublicType @scope(to: ["public"]) { id: ID! }
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

    // Negative: NoScopesMode EngineRegistry construction never enters the
    // scoped-mode branch. Verified in two steps:
    //   Step 1 — config.scopedSchemas is empty: no ScopedSchemaConfig entries whose
    //            build() could possibly be invoked.
    //   Step 2 — after factory.create(), registry has only SchemaId.Full: no scoped schemas
    //            were materialized, proving zero scoped-path invocations.
    @Test
    fun `NoScopesMode EngineRegistry build invokes zero ScopedSchemaConfig build calls`() {
        val config = SchemaConfiguration.fromResources(setOf("FULL"), emptyClassLoader())

        // Step 1: precondition — no ScopedSchemaConfig entries means build() cannot be invoked
        assertTrue(
            config.scopedSchemas.isEmpty(),
            "precondition: NoScopesMode config must have zero ScopedSchemaConfig entries"
        )

        val factory = EngineRegistry.Factory(createSchemaFactory(), createDocumentProviderFactory())
        val registry = factory.create(config)

        // Step 2: post-condition — only FULL registered, scoped branches never entered
        val ids = registry.getRegisteredSchemaIds()
        assertEquals(
            setOf<SchemaId>(SchemaId.Full),
            ids,
            "only SchemaId.Full must be in the registry for NoScopesMode — scoped path never entered"
        )
    }

    // Positive control: In ScopedMode, scoped schemas ARE materialized and appear
    // in the registry, confirming the scoped-mode branch IS entered when schemas are declared.
    // This is the counter-case: if scopes ARE declared, scoped schemas
    // exist in the registry (build() was called); if no scopes, they do not (build() was not called).
    @Test
    fun `positive control ScopedMode registry contains materialized scoped schemas`() {
        val config = SchemaConfiguration.fromSdl(
            SCOPED_SDL,
            scopes = setOf(
                SchemaConfiguration.ScopeConfig("api", setOf("public")),
                SchemaConfiguration.ScopeConfig("adminApi", setOf("admin"))
            )
        )
        // Precondition: ScopedMode config has non-empty scopedSchemas — build() targets exist
        assertEquals(2, config.scopedSchemas.size, "Positive control precondition: 2 scoped configs")

        val factory = EngineRegistry.Factory(createSchemaFactory(), createDocumentProviderFactory())
        val registry = factory.create(config)

        val ids = registry.getRegisteredSchemaIds()
        assertEquals(3, ids.size, "ScopedMode: FULL + api + adminApi = 3 schemas total")
        assertTrue(
            ids.any { it.id == "api" },
            "api scoped schema must be materialized and registered in ScopedMode"
        )
        assertTrue(
            ids.any { it.id == "adminApi" },
            "adminApi scoped schema must be materialized and registered in ScopedMode"
        )
    }
}
