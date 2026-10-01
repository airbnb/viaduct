package model.testing

import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.registry.ProviderFragment
import model.registry.VariableDefinition
import model.requireObjectField
import model.usedVariables
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResolverTestDslParsingTest {
    @Test
    fun `injected errors collapse direct and nested argument expressions`() {
        for (value in listOf("\"ERROR\"", "{values: \"ERROR\"}", "{values: [1, \"ERROR\"]}")) {
            val selection = "dependency(arg: $value)".replace("\"", "\\\"")
            val world =
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(of: "$selection", result: 1)
                      dependency(arg: Input!): Int! @resolver(result: 2)
                    }
                    input Input { values: [Int!]! }
                    """.trimIndent(),
                )
            val resolver = world.resolverRegistry.resolver(world.schema.requireObjectField("Query", "result"))
            assertEquals(Arguments.Error, resolver.objectFragment.single().key.arguments)
            assertTrue(resolver.variables.isEmpty())
        }
    }

    @Test
    fun `injected errors preserve aliased provider paths and real variables`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int!): Int!
                    @resolver(
                      of: "provided: source(arg: \"ERROR\") byArg(arg: ${'$'}seed) untouched(arg: ${'$'}__resolverTestError0) consume(arg: ${'$'}fromSource)"
                      pathVars: [{name: "fromSource", path: ["provided"]}]
                      providerVars: {__resolverTestError0: 4}
                      result: 1
                    )
                  source(arg: Int!): Int! @resolver(result: 2)
                  byArg(arg: Int!): Int! @resolver(result: 3)
                  untouched(arg: Int!): Int! @resolver(result: 4)
                  consume(arg: Int!): Int! @resolver(result: 5)
                }
                """.trimIndent(),
            )
        val owner = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(owner)
        val provider = assertInstanceOf(VariableDefinition.FromField::class.java, resolver.variables.getValue(Arguments.Variable.of(owner, "fromSource")))
        val provided = resolver.objectFragment.filter { it.key.field.name == "source" }.single()

        assertEquals(setOf("seed", "__resolverTestError0", "fromSource"), resolver.variables.keys.map { it.variableName }.toSet())
        assertEquals(ProviderFragment.OBJECT, provider.providerFragment)
        assertEquals(listOf("provided"), provider.responsePath)
        assertEquals(Arguments.Error, provider.path.single().arguments)
        assertEquals(provider.path.single(), provided.key)
        assertEquals(
            setOf(Arguments.Variable.of(owner, "__resolverTestError0")),
            resolver.objectFragment.filter { it.key.field.name == "untouched" }.single().key.arguments.usedVariables(),
        )
    }

    @Test
    fun `injected errors preserve nested selections aliases and symbolic guards`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(show: Boolean!): Int!
                    @resolver(of: "box { ... @include(if: ${'$'}show) { renamed: dependency(arg: \"ERROR\") } }", result: 1)
                  box: Box! @resolver(result: {})
                }
                type Box {
                  dependency(arg: Int!): Int! @resolver(result: 2)
                }
                """.trimIndent(),
            )
        val owner = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(owner)
        val dependency = resolver.objectFragment.single().subselections.single()

        assertEquals(Arguments.Error, dependency.key.arguments)
        assertEquals(setOf(Arguments.Variable.of(owner, "show")), dependency.inclusionCondition.usedVariables())
        assertEquals(setOf("show"), resolver.variables.keys.map { it.variableName }.toSet())
        val materialized =
            resolver.instantiateObjectMaterializationSelections(
                ResolverOccurrenceId.at(world.schema.testRoot(), listOf(ObjectEngineResult.GroundKey.of(owner, mapOf("show" to true)))),
            )
        assertEquals("renamed", materialized.single().subselections.single().responseKey)
    }

    @Test
    fun `injected argument errors do not become Boolean directive variables`() {
        for (selection in listOf(
            "dependency @include(if: \"ERROR\")",
            "dependency @skip(if: true) @include(if: \"ERROR\")",
            "... @include(if: \"ERROR\") { dependency }",
        )) {
            val escapedSelection = selection.replace("\"", "\\\"")
            val failure = assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(of: "$escapedSelection", result: 1)
                      dependency: Int! @resolver(result: 2)
                    }
                    """.trimIndent(),
                )
            }
            assertTrue(failure.message.orEmpty().contains("Directive variables must be Boolean"))
        }
    }
}
