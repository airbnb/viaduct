package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld

/**
 * Contract for nonempty object fragments with variables bound from resolver arguments.
 */
interface ObjectFragmentFromArgumentResolverContract :
    ResolverContract,
    NestedFromArgumentDemandResolverContract,
    PassiveFromArgumentDemandResolverContract,
    RecursiveListFromArgumentDemandResolverContract {
    @Test
    fun `resolves input selected with a fromArgument variable`() {
        val applicationArguments = ResolverApplicationArguments()
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!): Int!
                        @resolver(
                          of: "consume(value: ${'$'}seed)"
                          script: "(objectValue.consume == null ? 0 : objectValue.consume)"
                        )
                      consume(value: Int!): Int!
                        @resolver(script: "value + value")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.requireObjectField("Query", "result")
        val firstKey =
            ObjectEngineResult.GroundKey.of(
                resultField,
                mapOf("seed" to 7),
            )
        val secondKey = ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 8))
        val resolution =
            resolveAndValidateObserved(
                testWorld,
                """
                query Resolve(${'$'}first: Int!, ${'$'}second: Int!) {
                  first: result(seed: ${'$'}first)
                  second: result(seed: ${'$'}second)
                }
                """.trimIndent(),
                variables = mapOf("first" to 7, "second" to 8),
                resolutionObserver = applicationArguments,
            )
        val resolved = resolution.result

        assertEquals(14, resolved.getCell(firstKey).get())
        assertEquals(16, resolved.getCell(secondKey).get())
        applicationArguments.assertArguments(
            world.schema.requireField("Query", "consume"),
            mapOf("value" to 7),
            mapOf("value" to 8),
        )
        val resolver = world.resolverRegistry.resolver(resultField)
        listOf(
            firstKey to 7,
            secondKey to 8,
        ).forEach { (groundKey, expectedValue) ->
            val path = listOf(groundKey)
            val variableInstances =
                resolver
                    .declaredDependencies.instantiatedVariableDefinitions(
                        ResolverOccurrenceId.at(resolved, path),
                    )
                    .map { definition -> definition.variable }
            variableInstances.forEach { boundVariable ->
                assertEquals(
                    VariableBinding.of(expectedValue),
                    resolution.operation.variableBindings.getBinding(
                        requireNotNull(boundVariable.instanceId),
                    ),
                )
            }
        }
    }

    @Test
    fun `resolves a fromArgument variable whose name differs from its argument`() {
        val testWorld = TestWorld.fromDSL(
            """
            extend type Query {
              result(value: Int!): Int! @resolver(
                of: "consume(value: ${'$'}argumentValue)"
                variables: {name: "argumentValue", fromArgument: ["value"]}
                script: "objectValue.consume"
              )
              consume(value: Int!): Int! @resolver(script: "value")
            }
            """.trimIndent(),
        )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf("value" to 7),
            )
        val resolved =
            resolveAndValidate(testWorld, "query { result(value: 7) }")

        assertEquals(7, resolved.getCell(resultKey).get())
    }

    @Test
    fun `resolves a fromArgument variable through a nested input object`() {
        val testWorld = TestWorld.fromDSL(
            """
            extend type Query {
              result(input: Input!): Int! @resolver(
                of: "consume(value: ${'$'}nestedValue)"
                variables: {name: "nestedValue", fromArgument: ["input", "value"]}
                script: "objectValue.consume"
              )
              consume(value: Int!): Int! @resolver(script: "value")
            }
            input Input { value: Int! }
            """.trimIndent(),
        )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf("input" to mapOf("value" to 2)),
            )

        val resolved = resolveAndValidate(testWorld, "query { result(input: {value: 2}) }")

        assertEquals(2, resolved.getCell(resultKey).get())
    }

    @Test
    fun `binds null when a fromArgument path traverses a null input object`() {
        val testWorld = TestWorld.fromDSL(
            """
            extend type Query {
              result(input: Input): Int! @resolver(
                of: "consume(value: ${'$'}nestedValue)"
                variables: {name: "nestedValue", fromArgument: ["input", "value"]}
                script: "objectValue.consume == null ? 7 : objectValue.consume"
              )
              consume(value: Int): Int @resolver(script: "value")
            }
            input Input { value: Int! = 2 }
            """.trimIndent(),
        )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf("input" to null),
            )

        val resolved = resolveAndValidate(testWorld, "query { result(input: null) }")

        assertEquals(7, resolved.getCell(resultKey).get())
    }

    @Test
    fun `resolves a transitive chain of fromArgument variables`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      one(seed: Int!): Int!
                        @resolver(of: "two(value: ${'$'}seed)", script: "(objectValue.two == null ? 0 : objectValue.two)")
                      two(value: Int!): Int!
                        @resolver(of: "three(value: ${'$'}value)", script: "(objectValue.three == null ? 0 : objectValue.three)")
                      three(value: Int!): Int!
                        @resolver(script: "1 + value")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val oneKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "one"),
                mapOf("seed" to 7),
            )
        val resolved = resolveAndValidate(testWorld, "query { one(seed: 7) }")

        assertEquals(8, resolved.getCell(oneKey).get())
    }
}
