package semantics.resolver26

import kotlin.test.Test
import kotlin.test.assertTrue
import model.Assumptions
import model.fragmentFrom
import model.registry.ResolverRegistry
import model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class ParentConstructionDemandComplexityRegressionTest {
    @Test
    fun `an unused parent relation does not exponentially expand an acyclic resolver diamond`() {
        val depth = 15
        val fields = (0..depth).joinToString("\n") { index ->
            val inputs = ((index + 1)..minOf(index + 2, depth))
                .joinToString(" ") { "field$it" }
            "field$index: Int @resolver(of: \"$inputs\", result: 1)"
        }
        val original = TestWorld.fromDSL(
            """
            extend type Query {
              $fields
              unused: Parent @resolver(result: {})
            }
            type Parent {
              child: Child
            }
            type Child {
              parent: Parent @parent
            }
            """.trimIndent(),
        ).assumptions
        var lookups = 0
        val registry = object : ResolverRegistry by original.resolverRegistry {
            override fun resolver(field: ViaductSchema.ObjectField) = original.resolverRegistry.resolver(field).also { lookups++ }
        }
        val world = Assumptions.of(original.schema, registry, original.selectiveResolvers)
        val input = world.schema.fragmentFrom("fragment F on Query { field0 }").subselections

        val additional = input.liftParentConstructionDemand(world)

        assertTrue(additional.isEmpty(), "None of the reachable resolvers reads a parent")
        // Allow quadratic work rather than requiring the original memoizer's exact lookup count.
        // The registry has just depth + 1 reachable templates and at most two edges per template.
        val lookupBudget = (depth + 1) * (depth + 1)
        assertTrue(
            lookups <= lookupBudget,
            "Parent lifting looked up $lookups templates for ${depth + 1} reachable fields; " +
                "budget $lookupBudget. A shared dependency DAG must not become a path tree.",
        )
    }
}
