package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

class SuccessorDemandRegressionTest : ResolutionDispatcherResource {
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

    @Test
    fun `excluded child does not demand parent-derived value unconditionally`() {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query { root: Root @resolver(result: {}) }
            type Root { seed: Int child: Child }
            type Child {
              parent: Root @parent
              derived: Int @resolver(of: "parent { seed }", result: 1)
            }
            """.trimIndent(),
        )
        val root = fixture.schema.requireObjectField("Root", "seed").containingDef
        val input = fixture.schemas.fragmentFrom(
            "fragment F on Root { child @skip(if: true) { derived } }",
        ).subselections
        val output = input.successorDemand(fixture.assumptions).merge(root)
        val seed = output.byKey().values.find { it.key.field.name == "seed" }
        assertTrue(
            seed == null || seed.inclusionCondition === InclusionCondition.Never,
            "Excluded child produced seed with condition ${seed?.inclusionCondition}",
        )
    }

    @Test
    fun `publication preserves exclusion on parent-derived producer demand`() {
        val fixture = TestWorld.fromDSL(
            """
            extend type Query { root: Root @resolver(result: {seed: 7, child: {}}) }
            type Root { seed: Int child: Child }
            type Child {
              parent: Root @parent
              derived: Int @resolver(of: "parent { seed }", result: 1)
            }
            """.trimIndent(),
        )
        val observations = mutableListOf<ResolverInvocationObservation>()
        val observer = object : ResolverObserver {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                observations += observation
            }
        }
        val operation = SharedOperationContext.create(fixture.assumptions, resolverObserver = observer)
        operation.resolveWithTestDispatcher(
            fixture.schemas.fragmentFrom(
                "fragment F on Query { root { child @skip(if: true) { derived } } }",
            ).subselections,
        )
        val root = fixture.schema.requireObjectField("Root", "seed").containingDef
        val rootDemand = requireNotNull(
            observations.single { it.field.name == "root" }.suppliedDemand,
        ).merge(root)
        val seed = rootDemand.byKey().values.find { it.key.field.name == "seed" }
        assertTrue(
            seed == null || seed.inclusionCondition === InclusionCondition.Never,
            "Publication demanded seed with condition ${seed?.inclusionCondition}",
        )
    }
}
