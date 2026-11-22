package viaduct.engine.runtime2.model.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelection
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.fieldExpressions
import viaduct.engine.runtime2.model.instantiatedVariables
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireArg
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.testRoot
import viaduct.engine.runtime2.model.usedVariables

class ResolverVariableInstantiationTest {
    @Test
    fun `registry normalizes both base roots while retaining symbolic keys and materialization aliases`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { item: Item echo(value: Int): Int box: Box }
            type Item {
              result(seed: Int, other: Int): Int @resolver(
                of: "literal: echo(value: 3) first: echo(value: ${'$'}seed) duplicate: echo(value: ${'$'}seed) other: echo(value: ${'$'}other) box { first: value } box { second: value }"
                qf: "literal: echo(value: 3) first: echo(value: ${'$'}seed) duplicate: echo(value: ${'$'}seed) other: echo(value: ${'$'}other) box { first: value } box { second: value }"
                script: "null"
              )
              echo(value: Int): Int
              box: Box
            }
            type Box { value: Int }
            """.trimIndent(),
        )
        val result = world.schema.requireObjectField("Item", "result")
        val resolver = world.resolverRegistry.resolver(result)
        val occurrence = ResolverOccurrenceId.at(world.schema.testRoot(), listOf(ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 3, "other" to 3))))
        val materializations = listOf(
            resolver.declaredDependencies.instantiateObjectMaterializationSelections(occurrence),
            resolver.declaredDependencies.instantiateQueryMaterializationSelections(occurrence),
        )
        assertEquals(result.containingDef, resolver.declaredDependencies.objectFragment.type)
        assertEquals(world.schema.requireQueryTypeDef(), resolver.declaredDependencies.queryFragment.type)

        listOf(resolver.declaredDependencies.objectFragment, resolver.declaredDependencies.queryFragment).zip(materializations).forEach { (demand, materialization) ->
            assertEquals(4, demand.size)
            assertEquals(
                setOf(3, Arguments.Variable.of(result, "seed"), Arguments.Variable.of(result, "other")),
                demand.keys().filter { it.field.name == "echo" }.map { it.arguments.fieldExpressions().getValue("value") }.toSet(),
            )
            val box = demand.byKey().values.single { it.key.field.name == "box" }
            assertEquals(2, box.subselections.size)
            assertEquals(setOf("literal", "first", "duplicate", "other", "box"), materialization.collect(demand.type).responseKeys())
            assertEquals(6, materialization.size)
        }
    }

    @Test
    fun `normalized demand retains variables used only by materialization conditions on either root`() {
        val schemas = TestWorld.fromSDL("type Query { echo: Int } type Item { result(flag: Boolean!): Int echo: Int }").schemas
        val field = schemas.loweredSchema.requireObjectField("Item", "result")
        val variable = Arguments.Variable.of(field, "flag")

        fun input(type: String) =
            schemas.fragmentFrom(
                "fragment Input on $type { always: echo conditional: echo @include(if: ${'$'}flag) }",
                variableField = field,
            ).materializeSelections
        val resolver = FieldValueResolver.of(
            field,
            ResolverFragmentTemplates(input("Item"), input("Query"), mapOf(variable to VariableDefinition.FromArgument.of(field.requireArg("flag")))),
            schemas.loweredSchema.requireQueryTypeDef(),
            function = { _, _, _, _, _ -> null },
        )
        val occurrence = ResolverOccurrenceId.at(schemas.loweredSchema.testRoot(), listOf(ObjectEngineResult.GroundKey.of(field, mapOf("flag" to false))))
        val fragments = resolver.declaredDependencies.instantiateFragments(occurrence)
        listOf(fragments.objectFragment, fragments.queryFragment).forEach { fragment ->
            assertEquals(InclusionCondition.Always, fragment.constructionSelections.single().inclusionCondition)
            assertTrue(fragment.constructionSelections.usedVariables().isEmpty())
            assertEquals(variable.instantiate(occurrence), fragment.variableDefinitions.single().variable)
        }
        listOf(
            resolver.declaredDependencies.instantiateObjectMaterializationSelections(occurrence),
            resolver.declaredDependencies.instantiateQueryMaterializationSelections(occurrence),
        ).forEach { materialization ->
            assertEquals(2, materialization.size)
            val selections = mutableListOf<MaterializeSelection>()
            materialization.forEach(selections::add)
            assertEquals(setOf(variable.instantiate(occurrence)), selections.single { it.responseKey == "conditional" }.inclusionCondition.usedVariables())
        }
    }

    @Test
    fun `resolver occurrence identity distinguishes symbolic variables`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int): Int! @resolver(of: "literal: consume(value: 3) symbolic: consume(value: ${'$'}seed)", script: "1")
                  consume(value: Int): Int! @resolver(script: "1")
                }
                """.trimIndent(),
            )
        val result = testWorld.schema.requireObjectField("Query", "result")
        val resolver = testWorld.resolverRegistry.resolver(result)
        val firstPath =
            listOf(
                ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 3)),
            )
        val secondPath =
            listOf(
                ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 4)),
            )

        val root = testWorld.schema.testRoot()
        val firstFragment =
            resolver.declaredDependencies.instantiateFragmentsAt(root, firstPath).objectFragment.constructionSelections
        val equalFirstFragment =
            resolver.declaredDependencies.instantiateFragmentsAt(root, firstPath).objectFragment.constructionSelections
        val otherFragment =
            resolver.declaredDependencies.instantiateFragmentsAt(root, secondPath).objectFragment.constructionSelections
        val firstOccurrence = ResolverOccurrenceId.at(root, firstPath)

        assertTrue(
            firstFragment.instantiatedVariables().all { variable ->
                variable.instanceId?.resolverOccurrenceId == firstOccurrence
            },
        )
        assertEquals(
            firstFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
            equalFirstFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
        )
        assertNotEquals(
            firstFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
            otherFragment.merge(testWorld.schema.requireQueryTypeDef()).keys(),
        )
        assertEquals(
            firstFragment
                .filter { selection -> selection.key.instantiatedVariables().isEmpty() }
                .merge(testWorld.schema.requireQueryTypeDef())
                .keys(),
            otherFragment
                .filter { selection -> selection.key.instantiatedVariables().isEmpty() }
                .merge(testWorld.schema.requireQueryTypeDef())
                .keys(),
        )
    }

    @Test
    fun `instantiates definition fragment and provider path at one occurrence`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int): Int! @resolver(
                    of: "source(value: ${'$'}seed) consume(value: ${'$'}value)"
                    variables: [{name: "value", fromObjectField: ["source"]}]
                    script: "1"
                  )
                  source(value: Int): Int! @resolver(script: "1")
                  consume(value: Int): Int! @resolver(script: "1")
                }
                """.trimIndent(),
            )
        val resolver =
            testWorld.resolverRegistry.resolver(
                testWorld.schema.requireObjectField("Query", "result"),
            )
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                testWorld.schema.requireObjectField("Query", "result"),
                mapOf("seed" to 3),
            )
        val sitePath = listOf(resultKey)
        val root = testWorld.schema.testRoot()
        val resolverOccurrenceId = ResolverOccurrenceId.at(root, sitePath)
        val objectFragment = resolver.declaredDependencies.instantiateFragments(resolverOccurrenceId).objectFragment
        assertEquals(
            setOf("source", "consume"),
            resolver
                .declaredDependencies.instantiateObjectMaterializationSelections(resolverOccurrenceId)
                .collect(testWorld.schema.requireQueryTypeDef())
                .responseKeys(),
        )
        val definition = objectFragment.pathVariableDefinitions.single()
        val seed =
            Arguments.Variable.of(
                testWorld.schema.requireObjectField("Query", "result"),
                "seed",
            ).instantiate(resolverOccurrenceId)

        assertEquals(
            setOf(seed),
            definition.path.single().key.instantiatedVariables(),
        )
        assertEquals(
            setOf(seed, definition.variable),
            objectFragment.constructionSelections.instantiatedVariables(),
        )
        val reconstructed = resolver.declaredDependencies.instantiatedFieldPathVariableDefinitions(resolverOccurrenceId)
            .single { it.providerFragment == ProviderFragment.OBJECT }
        assertEquals(definition.variable, reconstructed.variable)
        assertEquals(definition.path.map { it.key }, reconstructed.path.map { it.key })
    }

    @Test
    fun `variable instantiation preserves generic field directives`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int): Int! @resolver(of: "renamed: consume(value: ${'$'}seed) @consumerPolicy", script: "1")
                  consume(value: Int): Int! @resolver(script: "1")
                }
                directive @consumerPolicy on FIELD
                """.trimIndent(),
            )
        val result = testWorld.schema.requireObjectField("Query", "result")
        val path =
            listOf(
                ObjectEngineResult.GroundKey.of(result, mapOf("seed" to 3)),
            )

        val resolver = testWorld.resolverRegistry.resolver(result)
        val fragment = resolver.declaredDependencies.instantiateFragmentsAt(testWorld.schema.testRoot(), path).objectFragment
        val selection =
            resolver
                .declaredDependencies.instantiateObjectMaterializationSelections(fragment.resolverOccurrenceId)
                .collect(testWorld.schema.requireQueryTypeDef())["renamed"]
        val variables = selection.key.arguments.instantiatedVariables()

        assertTrue(checkNotNull(selection.fieldDirectives).hasDirective("consumerPolicy"))
        assertEquals(1, variables.size)
        assertTrue(
            variables.all { variable ->
                variable.instanceId?.resolverOccurrenceId ==
                    ResolverOccurrenceId.at(testWorld.schema.testRoot(), path)
            },
        )
    }
}
