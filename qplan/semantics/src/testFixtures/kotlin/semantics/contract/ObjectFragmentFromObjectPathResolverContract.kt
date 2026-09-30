package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import model.EngineErrorData
import model.EngineOutputData
import model.EngineResult
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.VariableBinding
import model.registry.ProviderFragment
import model.requireObjectField
import model.testing.TestWorld
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.ResolverInvocationObservation

/**
 * Contract for resolver variables read from exact paths in the defining object fragment.
 */
interface ObjectFragmentFromObjectPathResolverContract :
    ResolverContract,
    DeferredNestedObjectPathDemandResolverContract,
    PassiveObjectPathProviderResolverContract,
    NestedObjectPathUseResolverContract,
    ObjectPathNodeInteractionResolverContract,
    AcyclicVariableDependencyResolverContract {
    @Test
    fun `binds a variable from a direct active scalar provider`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source: Int! @resolver(result: 7)
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value, ${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.requireObjectField("Query", "result")
        val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())
        val resolver = world.resolverRegistry.resolver(resultField)
        val resolution = resolveAndValidateObserved(testWorld, "query { result }")
        val resolved = resolution.result
        val boundVariable =
            resolver
                .fieldPathDefinitions(resolved, listOf(resultKey))
                .filter { definition ->
                    definition.providerFragment == ProviderFragment.OBJECT
                }
                .single()
                .variable

        assertEquals(14, resolved.getCell(resultKey).get())
        assertEquals(
            VariableBinding.of(7),
            resolution.operation.variableBindings.getBinding(
                requireNotNull(boundVariable.instanceId),
            ),
        )
    }

    @Test
    fun `grounds an object-path provider argument from the owner argument`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!): Int!
                        @resolver(
                          of: "source(value: ${'$'}seed) consume(value: ${'$'}provided)"
                          pathVars: [{name: "provided", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      consume(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf("seed" to 7),
            )

        val resolved = resolveAndValidate(testWorld, "query { result(seed: 7) }")

        assertEquals(7, resolved.getCell(resultKey).get())
    }

    @Test
    fun `grounds an object-path provider argument from another object path`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "consume(value: ${'$'}provided) source(value: ${'$'}sourceArg) seed"
                          pathVars: [
                            {name: "provided", path: ["source"]}
                            {name: "sourceArg", path: ["seed"]}
                          ]
                          result: "sum(consume)"
                        )
                      consume(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      source(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      seed: Int! @resolver(result: 11)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(testWorld, "query { result }")

        assertEquals(11, resolved.getCell(resultKey).get())
    }

    @Test
    fun `grounds a nested provider argument from another object path`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "box { source(value: ${'$'}sourceArg) } consume(value: ${'$'}provided) seed"
                          pathVars: [
                            {name: "provided", path: ["box", "source"]}
                            {name: "sourceArg", path: ["seed"]}
                          ]
                          result: "sum(consume)"
                        )
                      box: Box! @resolver(result: {})
                      consume(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      seed: Int! @resolver(result: 13)
                    }

                    type Box {
                      source(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(testWorld, "query { result }")

        assertEquals(13, resolved.getCell(resultKey).get())
    }

    @Test
    fun `waits for late orchestration of an already published provider object`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      box: Box! @resolver(result: {passive: 1})
                      seed: Int! @resolver(result: 13)
                      result: Int!
                        @resolver(
                          of: "box { source(value: ${'$'}sourceArg) } consume(value: ${'$'}provided) seed"
                          pathVars: [
                            {name: "provided", path: ["box", "source"]}
                            {name: "sourceArg", path: ["seed"]}
                          ]
                          result: "sum(consume)"
                        )
                      consume(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                    }

                    type Box {
                      passive: Int!
                      source(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved =
            resolveAndValidate(
                testWorld,
                "query { box { passive } result }",
            )

        assertEquals(13, resolved.getCell(resultKey).get())
    }

    @Test
    fun `grounds a nested provider argument from another nested provider`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "box { inner { source(value: ${'$'}sourceArg) } } consume(value: ${'$'}provided) seedBox { seed }"
                          pathVars: [
                            {name: "provided", path: ["box", "inner", "source"]}
                            {name: "sourceArg", path: ["seedBox", "seed"]}
                          ]
                          result: "sum(consume)"
                        )
                      box: Box! @resolver(result: {inner: {}})
                      consume(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                      seedBox: SeedBox! @resolver(result: {})
                    }

                    type Box {
                      inner: Inner!
                    }

                    type Inner {
                      source(value: Int!): Int! @resolver(result: "sum(${'$'}value)")
                    }

                    type SeedBox {
                      seed: Int! @resolver(result: 17)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(testWorld, "query { result }")

        assertEquals(17, resolved.getCell(resultKey).get())
    }

    @Test
    fun `binds null and error from nullable provider paths`() {
        listOf(false, true).forEach { isError ->
            val provided = null
            val dslValue = if (isError) "\"ERROR\"" else "null"
            var observedResultInput = false
            var consumedKey: String? = null
            var consumedValue: EngineOutputData? = null
            val invocationObserver = object : CorrectnessResolverObserver() {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    super.onResolverInvocation(observation)
                    val field = observation.field
                    val input = observation.input
                    if (
                        field.containingDef.name == "Query" &&
                        field.name == "result"
                    ) {
                        val consumed =
                            input.selectionValues().entries
                                .single { (key, _) -> key == "consume" }
                        consumedKey = consumed.key
                        consumedValue = consumed.value
                        observedResultInput = true
                    }
                }
            }
            val testWorld =
                TestWorld.fromDSL(
                    selectiveResolvers = selectiveResolvers,
                    schemaSDL =
                        """
                        extend type Query {
                          result: Int
                            @resolver(
                              of: "source consume(value: ${'$'}value)"
                              pathVars: [{name: "value", path: ["source"]}]
                              result: $dslValue
                            )
                          source: Int @resolver(result: $dslValue)
                          consume(value: Int): Int @resolver(result: $dslValue)
                        }
                        """.trimIndent(),
                )
            val world = testWorld.assumptions
            val resultField = world.schema.requireObjectField("Query", "result")
            val resultKey = ObjectEngineResult.GroundKey.of(resultField, emptyMap())
            val resolver = world.resolverRegistry.resolver(resultField)
            val resolution = resolveAndValidateObserved(testWorld, "query { result }", resolverObserver = invocationObserver)
            val resolved = resolution.result
            val boundVariable =
                resolver
                    .fieldPathDefinitions(resolved, listOf(resultKey))
                    .filter { definition ->
                        definition.providerFragment == ProviderFragment.OBJECT
                    }
                    .single()
                    .variable

            val resultValue = resolved.getCell(resultKey).get()
            if (isError) {
                assertIs<ErrorEngineResult>(resultValue)
            } else {
                assertEquals<EngineResult?>(null, resultValue)
            }
            assertEquals(
                if (isError) {
                    VariableBinding.Error
                } else {
                    VariableBinding.of(provided)
                },
                resolution.operation.variableBindings.getBinding(
                    requireNotNull(boundVariable.instanceId),
                ),
            )
            assertEquals("consume", consumedKey)
            assertEquals(true, observedResultInput)
            if (isError) {
                assertIs<EngineErrorData>(consumedValue)
            } else {
                assertEquals(null, consumedValue)
            }
        }
    }

    @Test
    fun `reads a nested provider after its active ancestor publishes passive content`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "box { value } consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["box", "value"]}]
                          result: "sum(consume)"
                        )
                      box: Box! @resolver(result: {value: 9})
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Box {
                      value: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(testWorld, "query { result }")

        assertEquals(9, resolved.getCell(resultKey).get())
    }

    @Test
    fun `converts a terminal scalar list to a ground input list`() {
        val applicationArguments = ResolverApplicationArguments()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source consume(values: ${'$'}values)"
                          pathVars: [{name: "values", path: ["source"]}]
                          result: "sum(consume)"
                        )
                      source: [Int!]! @resolver(result: [2, 3, 5])
                      consume(values: [Int!]!): Int! @resolver(result: 10)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(testWorld, "query { result }", resolverObserver = applicationArguments)

        assertEquals(10, resolved.getCell(resultKey).get())
        applicationArguments.assertArguments(
            world.schema.requireObjectField("Query", "consume"),
            mapOf("values" to listOf(2, 3, 5)),
        )
    }
}
