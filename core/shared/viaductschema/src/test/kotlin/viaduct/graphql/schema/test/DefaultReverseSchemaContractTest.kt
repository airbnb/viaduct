package viaduct.graphql.schema.test

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.SchemaFilter
import viaduct.graphql.schema.ViaductReverseSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

class DefaultReverseSchemaContractTest : ViaductReverseSchemaContract {
    override fun createReverseSchema(sdl: String): ViaductReverseSchema {
        val schema = ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(sdl))
        return ViaductReverseSchema.from(schema)
    }

    // =========================================================================
    // Filtered schema tests — standalone, not part of the contract
    // =========================================================================

    @Test
    fun `filtered schema - unfiltered def throws when passed to filtered reverse schema`() {
        val original = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                """
                type Removed { x: String }
                type Kept { y: String }
                type Query { kept: Kept }
                """.trimIndent()
            )
        )
        val removedFromOriginal = original.types["Removed"]!!
        assertNotNull(removedFromOriginal, "Removed should exist in original")

        val filtered = original.filter(excludeByName("Removed"))
        assertNull(filtered.types["Removed"], "Removed should not exist in filtered")

        val rev = ViaductReverseSchema.from(filtered)
        assertThrows<IllegalArgumentException>("inboundDefs should reject unfiltered def") {
            rev.inboundDefs(removedFromOriginal)
        }
        assertThrows<IllegalArgumentException>("referencingTopLevelDefs should reject unfiltered def") {
            rev.referencingTopLevelDefs(removedFromOriginal as ViaductSchema.TopLevelDef)
        }
    }

    @Test
    fun `filtered schema - defs from filtered schema work normally`() {
        val original = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                """
                type Removed { x: String }
                type Kept { y: String }
                type Query { kept: Kept }
                """.trimIndent()
            )
        )
        val filtered = original.filter(excludeByName("Removed"))
        val rev = ViaductReverseSchema.from(filtered)

        // Kept exists in filtered schema and can be queried normally
        val keptFiltered = filtered.types["Kept"]!!
        val inbound = rev.inboundFields(keptFiltered)
        assertTrue(inbound.any { it.name == "kept" }, "Query.kept should reference Kept")
    }

    @Test
    fun `filtered schema - kept def from original still rejected`() {
        // Even a type that exists in BOTH schemas has different identity objects;
        // the original's Kept is not the filtered's Kept.
        val original = ViaductSchema.fromTypeDefinitionRegistry(
            SchemaParser().parse(
                """
                type Kept { y: String }
                type Query { kept: Kept }
                """.trimIndent()
            )
        )
        val filtered = original.filter(includeAll())
        val rev = ViaductReverseSchema.from(filtered)

        val keptFromOriginal = original.types["Kept"]!!
        assertThrows<IllegalArgumentException>("original def should be rejected even if same-named type exists") {
            rev.inboundDefs(keptFromOriginal)
        }
    }

    companion object {
        private fun excludeByName(vararg names: String): SchemaFilter {
            val excluded = names.toSet()
            return object : SchemaFilter {
                override fun includeTypeDef(typeDef: ViaductSchema.TypeDef) = typeDef.name !in excluded

                override fun includeField(field: ViaductSchema.Field) = true

                override fun includeEnumValue(enumValue: ViaductSchema.EnumValue) = true

                override fun includeSuper(
                    record: ViaductSchema.OutputRecord,
                    superInterface: ViaductSchema.Interface
                ) = true
            }
        }

        private fun includeAll(): SchemaFilter =
            object : SchemaFilter {
                override fun includeTypeDef(typeDef: ViaductSchema.TypeDef) = true

                override fun includeField(field: ViaductSchema.Field) = true

                override fun includeEnumValue(enumValue: ViaductSchema.EnumValue) = true

                override fun includeSuper(
                    record: ViaductSchema.OutputRecord,
                    superInterface: ViaductSchema.Interface
                ) = true
            }
    }
}
