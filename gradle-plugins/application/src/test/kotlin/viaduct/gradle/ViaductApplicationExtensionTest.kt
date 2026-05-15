package viaduct.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.scopes.NoScopesMode
import viaduct.graphql.schema.scopes.ScopedMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for ViaductApplicationExtension scope declaration methods.
 */
class ViaductApplicationExtensionTest {
    private lateinit var ext: ViaductApplicationExtension

    @BeforeEach
    fun setUp() {
        val project = ProjectBuilder.builder().build()
        ext = ViaductApplicationExtension(project.objects)
    }

    // backward compatibility: never-invoked → NoScopesMode
    @Test
    fun `getSchemaScopeInfo returns NoScopesMode when no scopes declared`() {
        assertIs<NoScopesMode>(ext.getSchemaScopeInfo())
    }

    // scope declarations are available as ScopedMode
    @Test
    fun `getSchemaScopeInfo returns ScopedMode after declaredSchemaScopes`() {
        ext.declaredSchemaScopes(setOf("public", "internal"))
        val info = ext.getSchemaScopeInfo()
        assertIs<ScopedMode>(info)
        assertEquals(setOf("public", "internal"), info.scopeUniverse)
    }

    @Test
    fun `declaredScopedSchema is reflected in getSchemaScopeInfo`() {
        ext.declaredSchemaScopes(setOf("public", "internal"))
        ext.declaredScopedSchema("api", setOf("public"))
        val info = ext.getSchemaScopeInfo() as ScopedMode
        assertEquals(mapOf("api" to setOf("public")), info.scopedSchemas)
    }

    // scope universe captured in Gradle @Input property
    @Test
    fun `scopeUniverse input property reflects declared scopes`() {
        ext.declaredSchemaScopes(setOf("public", "internal"))
        val inputScopes = ext.scopeUniverse.get()
        assertEquals(setOf("public", "internal"), inputScopes)
    }

    @Test
    fun `scopedSchemaEntries input property contains serialized entries`() {
        ext.declaredSchemaScopes(setOf("public", "internal"))
        ext.declaredScopedSchema("api", setOf("public"))
        val entries = ext.scopedSchemaEntries.get()
        assertEquals(1, entries.size)
        assertTrue(entries.first().startsWith("api="))
        assertTrue(entries.first().contains("public"))
    }

    // invalid syntax fails at configuration time (fail-fast)
    @Test
    fun `declaredSchemaScopes rejects invalid scope ID syntax`() {
        assertThrows<GradleException> {
            ext.declaredSchemaScopes(setOf("INVALID"))
        }
    }

    @Test
    fun `declaredSchemaScopes rejects scope ID with digits`() {
        assertThrows<GradleException> {
            ext.declaredSchemaScopes(setOf("scope1"))
        }
    }

    @Test
    fun `declaredSchemaScopes rejects scope ID with hyphen`() {
        assertThrows<GradleException> {
            ext.declaredSchemaScopes(setOf("my-scope"))
        }
    }

    @Test
    fun `declaredScopedSchema rejects invalid schema ID syntax`() {
        ext.declaredSchemaScopes(setOf("public"))
        assertThrows<GradleException> {
            ext.declaredScopedSchema("Invalid-ID", setOf("public"))
        }
    }

    // Negative: duplicate scope IDs
    @Test
    fun `declaredSchemaScopes rejects duplicate scope IDs`() {
        ext.declaredSchemaScopes(setOf("public"))
        assertThrows<GradleException> {
            ext.declaredSchemaScopes(setOf("public"))
        }
    }

    @Test
    fun `declaredSchemaScopes rejects duplicates within single call`() {
        ext.declaredSchemaScopes(setOf("public"))
        assertThrows<GradleException> {
            // "public" already in universe
            ext.declaredSchemaScopes(setOf("internal", "public"))
        }
    }

    // Negative: scoped-schema scopes not in universe
    @Test
    fun `declaredScopedSchema rejects scopes not in universe`() {
        ext.declaredSchemaScopes(setOf("public"))
        assertThrows<GradleException> {
            ext.declaredScopedSchema("api", setOf("public", "internal"))
        }
    }

    @Test
    fun `declaredScopedSchema rejects all scopes when universe empty`() {
        assertThrows<GradleException> {
            ext.declaredScopedSchema("api", setOf("public"))
        }
    }

    // Multiple scope declarations accumulate correctly
    @Test
    fun `declaredSchemaScopes can be called multiple times to build universe`() {
        ext.declaredSchemaScopes(setOf("public"))
        ext.declaredSchemaScopes(setOf("internal"))
        val info = ext.getSchemaScopeInfo() as ScopedMode
        assertEquals(setOf("public", "internal"), info.scopeUniverse)
    }

    // scopeUniverse is a Gradle SetProperty (task @Input compatible)
    @Test
    fun `scopeUniverse property is non-null and writable`() {
        assertNotNull(ext.scopeUniverse)
        ext.declaredSchemaScopes(setOf("public"))
        assertEquals(setOf("public"), ext.scopeUniverse.get())
    }

    @Test
    fun `declaredScopedSchema stores scopes in sorted iteration order`() {
        ext.declaredSchemaScopes(setOf("alpha", "zeta"))
        ext.declaredScopedSchema("api", setOf("zeta", "alpha"))
        val info = ext.getSchemaScopeInfo() as ScopedMode
        assertEquals(listOf("alpha", "zeta"), info.scopedSchemas["api"]!!.toList())
    }
}
