package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.VariableBinding
import model.registry.VariableDefinition
import model.requireObjectField
import model.testing.TestWorld

/** Deterministic Resolver26 coverage for variables supplied by a tenant provider function. */
interface VariablesProviderResolverContract : ResolverContract {
    @Test
    fun `binds provider variables for each grounded resolver occurrence`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!): Int!
                        @resolver(
                          of: "left(value: ${'$'}double) right(value: ${'$'}constant)"
                          providerVars: {
                            double: "sum(${'$'}seed, ${'$'}seed)"
                            constant: 3
                          }
                          result: "sum(left, right)"
                        )
                      left(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      right(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.requireObjectField("Query", "result")
        val firstKey = ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 4))
        val secondKey = ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 5))
        val resolution =
            resolveAndValidateObserved(
                testWorld,
                "query { first: result(seed: 4) second: result(seed: 5) }",
            )

        assertEquals(11, resolution.result.getCell(firstKey).get())
        assertEquals(13, resolution.result.getCell(secondKey).get())
        listOf(
            firstKey to mapOf("double" to 8, "constant" to 3),
            secondKey to mapOf("double" to 10, "constant" to 3),
        ).forEach { (key, expected) ->
            val definitions =
                world.resolverRegistry
                    .resolver(resultField)
                    .instantiatedVariableDefinitions(
                        ResolverOccurrenceId.at(resolution.result, listOf(key)),
                    ).filter { definition ->
                        definition.definition == VariableDefinition.FromProvider
                    }
            assertEquals(
                expected.keys,
                definitions.mapTo(linkedSetOf()) { it.variable.variableName },
            )
            definitions.forEach { definition ->
                assertEquals(
                    VariableBinding.of(expected.getValue(definition.variable.variableName)),
                    resolution.operation.variableBindings.getBinding(
                        requireNotNull(definition.variable.instanceId),
                    ),
                )
            }
        }
    }

    @Test
    fun `provider variables may be null`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int
                        @resolver(
                          of: "consume(value: ${'$'}provided)"
                          providerVars: {provided: null}
                          result: "value(consume)"
                        )
                      consume(value: Int): Int @resolver(result: "value(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.requireObjectField("Query", "result")
        val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())

        val resolved = resolveAndValidate(testWorld, "query { result }")

        assertNull(resolved.getCell(resultKey).get())
    }

    @Test
    fun `provider failure completes its resolver field as an error`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int
                        @resolver(
                          of: "consume(value: ${'$'}provided)"
                          providerVars: {provided: "ERROR"}
                          result: "value(consume)"
                        )
                      consume(value: Int): Int @resolver(result: "value(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.requireObjectField("Query", "result")
        val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())

        val resolved = resolveAndValidate(testWorld, "query { result }")

        assertIs<ErrorEngineResult>(resolved.getCell(resultKey).get())
    }
}
