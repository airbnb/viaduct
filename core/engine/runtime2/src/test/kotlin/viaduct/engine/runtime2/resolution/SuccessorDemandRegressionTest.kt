package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Assumptions
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.graphql.schema.ViaductSchema

class SuccessorDemandRegressionTest {
    @Test
    fun `shared resolver and checker dependencies stay bounded across a diamond`() {
        val depth = 12

        fun inputs(index: Int) = ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it" }
        val fields = (0..depth).joinToString("\n") { "field$it: Int @resolver(of: \"${inputs(it)}\", result: 1)" }
        val originalFixture = TestWorld
            .fromDSL(
                "extend type Query { $fields }",
                fieldCheckers = { schema ->
                    (0 until depth).associate { index ->
                        val field = schema.loweredSchema.requireObjectField("Query", "field$index")
                        val pair = ResolverFragmentTemplates(
                            schema.fragmentFrom("fragment Input on Query { ${inputs(index)} }").materializeSelections,
                            viaduct.engine.runtime2.model.materializeSelectionForestOf(),
                        )
                        field to FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef(), mapOf("first" to pair, "second" to pair)) { _, _, _ -> CheckerResult.Success }
                    }
                },
            )
        val original = originalFixture.assumptions
        var lookups = 0
        val registry = object : ResolverRegistry by original.resolverRegistry {
            override fun resolver(field: ViaductSchema.ObjectField) = original.resolverRegistry.resolver(field).also { lookups++ }

            override fun fieldChecker(field: ViaductSchema.ObjectField) = original.resolverRegistry.fieldChecker(field).also { lookups++ }
        }
        val world = Assumptions.of(original.schema, registry, original.selectiveResolvers)
        val actual = originalFixture.schemas
            .fragmentFrom("fragment Output on Query { field0 }")
            .subselections
            .successorDemand(world)
        assertEquals(
            (0..depth).map { "field$it" }.toSet(),
            actual
                .merge(world.schema.requireQueryTypeDef())
                .keys()
                .map { it.field.name }
                .toSet()
        )
        assertTrue(lookups <= 4 * (depth + 1) * (depth + 1), "Shared dependency DAG required $lookups registry lookups")
        assertTrue(actual.size <= 2 * (depth + 1), "Duplicate input pairs expanded to ${actual.size} producer selections")
    }
}
