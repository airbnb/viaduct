package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.VariablesProviderFunction
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.registry.fromArgument
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf

interface QueryFragmentVariablesResolverContract : ResolverContract {
    @Test
    fun `Query inputs bind from arguments and close transitive variable demand`() {
        val fixture = queryVariablesWorld()
        val result = resolveAndValidate(fixture, "{ first: consumer(seed: 4) second: consumer(seed: 5) reference(seed: 4) }")

        assertEquals(4, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("seed" to 4))).get())
        assertEquals(5, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("seed" to 5))).get())
        assertEquals(4, result.getCell(fixture.schema.contractKey("Query", "reference", mapOf("seed" to 4))).get())
    }

    @Test
    fun `Query inputs combine argument and provider variables in one occurrence`() {
        val fixture =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL = "type Query { consumer(seed: Int!): Int, echo(value: Int): Int }",
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(
                        schema.requireObjectField("Query", "consumer") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = schemas.fragmentFrom("fragment Input on Query { argument: echo(value: ${'$'}argument) provided: echo(value: ${'$'}provided) }"),
                            ) { _, query, _ ->
                                val values = query.selectionValues()
                                (values.getValue("argument") as Int) + (values.getValue("provided") as Int)
                            }.withVariablesProvider(setOf("provided")) { arguments ->
                                mapOf("provided" to (arguments.fieldValues.getValue("seed") as Int) + 1)
                            },
                        schema.requireObjectField("Query", "echo") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments -> arguments.fieldValues.getValue("value") },
                    )
                },
                variableProviders = { schemas ->
                    val consumer = schemas.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(Arguments.Variable.of(consumer, "argument") to schemas.loweredSchema.fromArgument(consumer, "seed"))
                },
            )

        val result = resolveAndValidate(fixture, "{ consumer(seed: 4) }")

        assertEquals(9, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("seed" to 4))).get())
    }

    @Test
    fun `suspending Query providers run once per active argument distinct occurrence`() {
        val calls = mutableListOf<Int>()
        val fixture = queryVariablesWorld { arguments ->
            delay(1)
            val seed = arguments.fieldValues.getValue("seed") as Int
            calls += seed
            mapOf("value" to seed * 2)
        }
        val resolution = resolveAndValidateObserved(fixture, "{ first: consumer(seed: 4) same: consumer(seed: 4) second: consumer(seed: 5) }")

        assertEquals(8, resolution.result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("seed" to 4))).get())
        assertEquals(10, resolution.result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("seed" to 5))).get())
        assertEquals(listOf(4, 5), calls.sorted())
    }

    @Test
    fun `Query provider values may be null`() {
        val fixture = queryVariablesWorld { mapOf("value" to null) }
        val result = resolveAndValidate(fixture, "{ consumer(seed: 4) }")

        assertNull(result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("seed" to 4))).get())
    }

    @Test
    fun `failed and invalid Query providers isolate their owning field and complete bindings`() {
        val providers: List<VariablesProviderFunction> =
            listOf(
                { error("provider failed") },
                { throw CancellationException("local provider cancellation") },
                { emptyMap() },
                { mapOf("value" to 3, "extra" to 1) },
            )
        providers.forEach { provider ->
            val fixture = queryVariablesWorld(provider)
            val resolution = resolveAndValidateObserved(fixture, "{ consumer(seed: 4) echo(value: 7) }")

            assertIs<ErrorEngineResult>(resolution.result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("seed" to 4))).get())
            assertEquals(7, resolution.result.getCell(fixture.schema.contractKey("Query", "echo", mapOf("value" to 7))).get())
            val definitions = fixture.resolverRegistry.resolver(fixture.schema.requireObjectField("Query", "consumer"))
                .instantiatedVariableDefinitions(
                    ResolverOccurrenceId.at(
                        resolution.result,
                        listOf(fixture.schema.contractKey("Query", "consumer", mapOf("seed" to 4))),
                    ),
                )
            assertEquals(
                VariableBinding.Error,
                resolution.operation.variableBindings.getBinding(requireNotNull(definitions.single().variable.instanceId)),
            )
        }
    }

    @Test
    fun `failed and invalid reference target providers retain replayable hop evidence`() {
        val providers: List<VariablesProviderFunction> =
            listOf(
                { error("provider failed") },
                { throw CancellationException("local provider cancellation") },
                { emptyMap() },
                { mapOf("value" to 3, "extra" to 1) },
            )
        providers.forEach { provider ->
            val fixture = queryVariablesWorld(provider)
            val resolution = resolveAndValidateObserved(fixture, "{ reference(seed: 4) echo(value: 7) }")

            assertIs<ErrorEngineResult>(resolution.result.getCell(fixture.schema.contractKey("Query", "reference", mapOf("seed" to 4))).get())
            assertEquals(7, resolution.result.getCell(fixture.schema.contractKey("Query", "echo", mapOf("value" to 7))).get())
            val observer = assertIs<CorrectnessResolverObserver>(resolution.operation.resolverObserver)
            val hop = observer.rootFieldReferenceInvocations().single()
            val occurrence = ResolverOccurrenceId.at(hop.invocationRoot, hop.invocationPath)
            val definitions = fixture.resolverRegistry.resolver(hop.invocationKey.field).instantiatedVariableDefinitions(occurrence)
            assertEquals(
                VariableBinding.Error,
                resolution.operation.variableBindings.getBinding(requireNotNull(definitions.single().variable.instanceId)),
            )
            assertFalse(occurrence in observer.invokedResolverOccurrences())
        }
    }

    @Test
    fun `reference targets invoke providers in independent occurrences`() {
        val calls = mutableListOf<Int>()
        val fixture = queryVariablesWorld { arguments ->
            val seed = arguments.fieldValues.getValue("seed") as Int
            calls += seed
            mapOf("value" to seed * 2)
        }
        val result = resolveAndValidate(fixture, "{ reference(seed: 4) other: reference(seed: 5) }")

        assertEquals(8, result.getCell(fixture.schema.contractKey("Query", "reference", mapOf("seed" to 4))).get())
        assertEquals(10, result.getCell(fixture.schema.contractKey("Query", "reference", mapOf("seed" to 5))).get())
        assertEquals(listOf(4, 5), calls.sorted())
    }

    @Test
    fun `passive fields do not invoke their standard resolver provider`() {
        val fixture =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL = "type Query { item: Item!, echo(value: Int): Int } type Item { computed: Int }",
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(
                        schema.requireObjectField("Query", "item") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            schema.objectOf("Item") { "computed" setTo 42 }
                        },
                        schema.requireObjectField("Item", "computed") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Item"),
                                queryFragment = schemas.fragmentFrom("fragment Input on Query { echo(value: ${'$'}value) }"),
                            ) { _, _, _ -> error("passive resolver must not run") }
                                .withVariablesProvider(setOf("value")) { error("passive provider must not run") },
                    )
                },
            )

        val result = resolveAndValidate(fixture, "{ item { computed } }")

        val item = assertIs<ObjectEngineResult>(result.getCell(fixture.schema.contractKey("Query", "item")).get())
        assertEquals(42, item.getCell(fixture.schema.contractKey("Item", "computed")).get())
    }

    private fun queryVariablesWorld(provider: VariablesProviderFunction? = null): TestWorld =
        TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL = "type Query { consumer(seed: Int!): Int, middle(value: Int): Int, echo(value: Int): Int, reference(seed: Int!): Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val consumer = schema.requireObjectField("Query", "consumer")
                val consumerResolver =
                    fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom("fragment Input on Query { middle(value: ${'$'}value) }"),
                    ) { _, query, _ -> query.selectionValues().getValue("middle") }
                mapOf(
                    consumer to if (provider == null) consumerResolver else consumerResolver.withVariablesProvider(setOf("value"), provider),
                    schema.requireObjectField("Query", "middle") to
                        fieldResolverOf(
                            objectFragment = schema.emptyFragmentOf("Query"),
                            queryFragment = schemas.fragmentFrom("fragment Input on Query { echo(value: ${'$'}value) }"),
                        ) { _, query, _ -> query.selectionValues().getValue("echo") },
                    schema.requireObjectField("Query", "echo") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments -> arguments.fieldValues.getValue("value") },
                    schema.requireObjectField("Query", "reference") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments -> RootFieldReferenceData.of(listOf(consumer), arguments) },
                )
            },
            variableProviders = { schemas ->
                val schema = schemas.loweredSchema
                val consumer = schema.requireObjectField("Query", "consumer")
                val middle = schema.requireObjectField("Query", "middle")
                buildMap {
                    if (provider == null) put(Arguments.Variable.of(consumer, "value"), schema.fromArgument(consumer, "seed"))
                    put(Arguments.Variable.of(middle, "value"), schema.fromArgument(middle, "value"))
                }
            },
        )
}
