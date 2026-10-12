package viaduct.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.scoping.SchemaScoping
import viaduct.scoping.SchemaScopingManifest

/**
 * Covers selection against the declared scoped schemas: that what `scopes.yaml` declared comes back id
 * for id, and that every way of naming something it does not declare fails loudly rather than resolving
 * to fewer views than the caller asked for.
 */
internal class SchemaScopeInfoFromResourcesTest {
    private val declared = SchemaScopeInfo.declaredIn(
        SchemaScoping(
            scopeUniverse = setOf("default", "extras"),
            scopedSchemas = mapOf(
                "publicSchemaWithExtras" to setOf("default", "extras"),
                "publicSchema" to setOf("default"),
            ),
        ),
    )

    @Test
    fun `the declared scoped schemas come back id for id, ordered`() {
        assertEquals(
            listOf(
                SchemaScopeInfo.Scoped("publicSchema", setOf("default")),
                SchemaScopeInfo.Scoped("publicSchemaWithExtras", setOf("default", "extras")),
            ),
            declared,
        )
    }

    @Test
    fun `a declared selection resolves to exactly those views`() {
        assertEquals(
            listOf(SchemaScopeInfo.Scoped("publicSchema", setOf("default"))),
            SchemaScopeInfo.select(declared, setOf("publicSchema")),
        )
    }

    @Test
    fun `an undeclared selection is rejected and names every unknown id`() {
        val message = assertThrows<IllegalArgumentException> {
            SchemaScopeInfo.select(declared, setOf("nope", "alsoNope"))
        }.message!!

        assertTrue(message.contains("[alsoNope, nope]"), message)
        assertTrue(message.contains("publicSchema"), message)
    }

    @Test
    fun `a selection mixing declared and undeclared ids is rejected rather than partially resolved`() {
        val message = assertThrows<IllegalArgumentException> {
            SchemaScopeInfo.select(declared, setOf("publicSchema", "renamed"))
        }.message!!

        assertTrue(message.contains("[renamed]"), message)
    }

    @Test
    fun `selecting nothing selects nothing`() {
        assertEquals(emptyList<SchemaScopeInfo>(), SchemaScopeInfo.select(declared, emptySet()))
    }

    @Test
    fun `an application that declares no scopes is told where to declare them`() {
        // This module's test classpath carries no manifest, so this is the real missing-manifest path.
        val message = assertThrows<IllegalStateException> { SchemaScopeInfo.fromResources() }.message!!

        assertTrue(message.contains(SchemaScopingManifest.RESOURCE_PATH), message)
        assertTrue(message.contains("scopes.yaml"), message)
        assertTrue(message.contains("withScopedSchemas"), message)
    }
}
