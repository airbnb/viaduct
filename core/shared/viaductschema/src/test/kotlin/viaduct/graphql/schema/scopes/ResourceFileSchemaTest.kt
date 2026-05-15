package viaduct.graphql.schema.scopes

import com.fasterxml.jackson.databind.cfg.PackageVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResourceFileSchemaTest {

    // default create includes FULL key with empty set
    @Test
    fun `default create always includes FULL key with empty set`() {
        val schema = ResourceFileSchema.create()
        assertTrue(schema.declaredScopedSchemas.containsKey(ResourceFileSchema.FULL_SCHEMA_ID))
        assertEquals(emptySet<String>(), schema.declaredScopedSchemas[ResourceFileSchema.FULL_SCHEMA_ID])
    }

    @Test
    fun `create with explicit scopedSchemas still injects FULL if absent`() {
        val schema = ResourceFileSchema.create(
            declaredScopedSchemas = mapOf("other" to setOf("public"))
        )
        assertTrue(schema.declaredScopedSchemas.containsKey(ResourceFileSchema.FULL_SCHEMA_ID))
    }

    @Test
    fun `create preserves existing FULL entry if present`() {
        val schema = ResourceFileSchema.create(
            declaredScopedSchemas = mapOf(ResourceFileSchema.FULL_SCHEMA_ID to setOf("public"))
        )
        assertEquals(setOf("public"), schema.declaredScopedSchemas[ResourceFileSchema.FULL_SCHEMA_ID])
    }

    // serialized JSON contains version field equal to CURRENT_VERSION
    @Test
    fun `serialized JSON contains version field`() {
        val schema = ResourceFileSchema.create()
        val json = ResourceFileSchema.objectMapper().writeValueAsString(schema)
        assertTrue(json.contains("\"version\""))
        assertTrue(json.contains(ResourceFileSchema.CURRENT_VERSION))
    }

    // scope sets are sorted alphabetically in JSON
    @Test
    fun `scope sets serialized in alphabetical order`() {
        val schema = ResourceFileSchema.create(
            declaredSchemaScopes = setOf("zeta", "alpha", "mu")
        )
        val json = ResourceFileSchema.objectMapper().writeValueAsString(schema)
        val alphaIndex = json.indexOf("\"alpha\"")
        val muIndex = json.indexOf("\"mu\"")
        val zetaIndex = json.indexOf("\"zeta\"")
        assertTrue(alphaIndex < muIndex, "alpha should appear before mu in JSON")
        assertTrue(muIndex < zetaIndex, "mu should appear before zeta in JSON")
    }

    // Verify key ordering comes from the data (sorted map insertion), not from a Jackson feature flag.
    @Test
    fun `declaredScopedSchemas keys are serialized in alphabetical order from sorted construction`() {
        val schema = ResourceFileSchema.create(
            declaredScopedSchemas = mapOf("zeta" to emptySet(), "alpha" to emptySet())
        )
        val json = ResourceFileSchema.objectMapper().writeValueAsString(schema)
        val deserialized = ResourceFileSchema.objectMapper().readValue(json, ResourceFileSchema::class.java)
        assertEquals(schema, deserialized)
        val fullIdx = json.indexOf("\"FULL\"")
        val alphaIdx = json.indexOf("\"alpha\"")
        val zetaIdx = json.indexOf("\"zeta\"")
        assertTrue(fullIdx < alphaIdx, "FULL should appear before alpha in JSON")
        assertTrue(alphaIdx < zetaIdx, "alpha should appear before zeta in JSON")
    }

    // Verify runtime Jackson version is 2.17.3
    @Test
    fun `jackson runtime version is 2_17_3`() {
        assertEquals("2.17.3", PackageVersion.VERSION.toString())
    }

    // Round-trip test
    @Test
    fun `round-trip serialization preserves all fields`() {
        val original = ResourceFileSchema.create(
            declaredSchemaScopes = setOf("public", "internal"),
            declaredScopedSchemas = mapOf(
                ResourceFileSchema.FULL_SCHEMA_ID to setOf("public", "internal"),
                "other" to setOf("public")
            ),
            version = ResourceFileSchema.CURRENT_VERSION
        )
        val mapper = ResourceFileSchema.objectMapper()
        val json = mapper.writeValueAsString(original)
        val deserialized = mapper.readValue(json, ResourceFileSchema::class.java)
        assertEquals(original, deserialized)
    }
}
