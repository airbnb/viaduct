package viaduct.engine.runtime2.model.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.requireArg
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.testRoot
import viaduct.engine.runtime2.model.usedVariables

class VariableInstantiationTest {
    private val world =
        TestWorld.fromDSL(
            """
            extend type Query {
              result(seed: Int): Int! @resolver(of: "first: consume(value: ${'$'}seed) second: consume(value: ${'$'}seed)", script: "1")
              consume(value: Int): Int! @resolver(script: "1")
            }
            """.trimIndent(),
        )

    @Test
    fun `one resolver occurrence owns every use of a variable`() {
        val resolver =
            world.resolverRegistry.resolver(
                world.schema.requireObjectField("Query", "result"),
            )
        val occurrence =
            ResolverOccurrenceId.at(
                world.schema.testRoot(),
                listOf(ListEngineResult.Index.of(1)),
            )

        val fragment = resolver.declaredDependencies.instantiateFragments(occurrence).objectFragment
        val definition = fragment.variableDefinitions.single()
        val variables = fragment.constructionSelections.usedVariables()

        assertEquals(1, variables.size)
        assertEquals(definition.variable, variables.single())
    }

    @Test
    fun `different resolver occurrences own different variable instances`() {
        val resolver =
            world.resolverRegistry.resolver(
                world.schema.requireObjectField("Query", "result"),
            )

        val first =
            resolver
                .declaredDependencies.instantiateFragmentsAt(
                    world.schema.testRoot(),
                    listOf(ListEngineResult.Index.of(1)),
                )
                .objectFragment
                .variableDefinitions
                .single()
                .variable
        val second =
            resolver
                .declaredDependencies.instantiateFragmentsAt(
                    world.schema.testRoot(),
                    listOf(ListEngineResult.Index.of(2)),
                )
                .objectFragment
                .variableDefinitions
                .single()
                .variable

        assertNotEquals(first.instanceId, second.instanceId)
    }

    @Test
    fun `variable definitions preserve their components and require instances`() {
        val result = world.schema.requireObjectField("Query", "result")
        val consume = world.schema.requireObjectField("Query", "consume")
        val template = Arguments.Variable.of(result, "seed")
        val variable =
            template.instantiate(
                ResolverOccurrenceId.at(world.schema.testRoot(), emptyList()),
            )
        val providerPath =
            listOf(
                InstantiatedFieldPathElement.of(
                    ObjectEngineResult.Key.of(consume, mapOf("value" to 1)),
                    viaduct.engine.runtime2.model.InclusionCondition.Always,
                )
            )
        val argumentDefinition =
            VariableDefinition.FromArgument.of(result.requireArg("seed"))

        val definition = InstantiatedFieldPathDefinition.of(variable, ProviderFragment.OBJECT, providerPath)
        assertEquals(variable, definition.variable)
        assertEquals(ProviderFragment.OBJECT, definition.providerFragment)
        assertEquals(providerPath.single().key, definition.path.single().key)
        assertEquals(true, definition.path.single().inclusionCondition.include(emptyMap()))
        assertEquals(
            VariableInstanceDefinition.of(variable, argumentDefinition),
            VariableInstanceDefinition.of(variable, argumentDefinition),
        )
        assertNotNull(variable.instanceId)
        assertFailsWith<IllegalArgumentException> {
            InstantiatedFieldPathDefinition.of(template, ProviderFragment.OBJECT, providerPath)
        }
        assertFailsWith<IllegalArgumentException> {
            VariableInstanceDefinition.of(template, argumentDefinition)
        }
    }
}
