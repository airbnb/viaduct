package viaduct.graphql.schema.scopes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResourceFileSchemaTest {

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

    @Test
    fun `serialized JSON contains version field`() {
        val schema = ResourceFileSchema.create()
        val json = ResourceFileSchema.toJsonString(schema)
        assertTrue(json.contains("\"version\""))
        assertTrue(json.contains(ResourceFileSchema.CURRENT_VERSION))
    }

    @Test
    fun `scope sets serialized in alphabetical order`() {
        val schema = ResourceFileSchema.create(
            declaredSchemaScopes = setOf("zeta", "alpha", "mu")
        )
        val json = ResourceFileSchema.toJsonString(schema)
        val alphaIndex = json.indexOf("\"alpha\"")
        val muIndex = json.indexOf("\"mu\"")
        val zetaIndex = json.indexOf("\"zeta\"")
        assertTrue(alphaIndex < muIndex, "alpha should appear before mu in JSON")
        assertTrue(muIndex < zetaIndex, "mu should appear before zeta in JSON")
    }

    // Key ordering comes from sorted map construction, not from a Jackson feature flag —
    // round-trip equality plus index-based assertions below verify the ordering is data-driven.
    @Test
    fun `declaredScopedSchemas keys are serialized in alphabetical order from sorted construction`() {
        val schema = ResourceFileSchema.create(
            declaredScopedSchemas = mapOf("zeta" to emptySet(), "alpha" to emptySet())
        )
        val json = ResourceFileSchema.toJsonString(schema)
        val deserialized = ResourceFileSchema.parse(json)
        assertEquals(schema, deserialized)
        val fullIdx = json.indexOf("\"FULL\"")
        val alphaIdx = json.indexOf("\"alpha\"")
        val zetaIdx = json.indexOf("\"zeta\"")
        assertTrue(fullIdx < alphaIdx, "FULL should appear before alpha in JSON")
        assertTrue(alphaIdx < zetaIdx, "alpha should appear before zeta in JSON")
    }

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
        val json = ResourceFileSchema.toJsonString(original)
        val deserialized = ResourceFileSchema.parse(json)
        assertEquals(original, deserialized)
    }
}
