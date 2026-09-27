package semantics.resolver26

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.Assumptions
import model.fragmentFrom
import model.merge
import model.registry.FieldChecker
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverRegistry
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

class SuccessorDemandRegressionTest {
    @Test
    fun `shared resolver and checker dependencies stay bounded across a diamond`() {
        val depth = 12

        fun inputs(index: Int) = ((index + 1)..minOf(index + 2, depth)).joinToString(" ") { "field$it" }
        val fields = (0..depth).joinToString("\n") { "field$it: Int @resolver(of: \"${inputs(it)}\", result: 1)" }
        val original = TestWorld
            .fromDSL(
                "extend type Query { $fields }",
                fieldCheckers = { schema ->
                    (0 until depth).associate { index ->
                        val field = schema.requireObjectField("Query", "field$index")
                        val pair = ResolverFragmentTemplates(
                            schema.fragmentFrom("fragment Input on Query { ${inputs(index)} }").materializeSelections,
                            model.materializeSelectionForestOf(),
                        )
                        field to FieldChecker.of(field, schema.requireQueryTypeDef(), mapOf("first" to pair, "second" to pair)) { _, _, _ -> CheckerResult.Success }
                    }
                },
            ).assumptions
        var lookups = 0
        val registry = object : ResolverRegistry by original.resolverRegistry {
            override fun resolver(field: ViaductSchema.ObjectField) = original.resolverRegistry.resolver(field).also { lookups++ }

            override fun fieldChecker(field: ViaductSchema.ObjectField) = original.resolverRegistry.fieldChecker(field).also { lookups++ }
        }
        val world = Assumptions.of(original.schema, registry, original.selectiveResolvers)
        val actual = world.schema
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
