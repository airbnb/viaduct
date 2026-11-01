package viaduct.engine.runtime2.contract

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
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
    fun `conditional-only Query inputs retain transitive demand with argument include conditions`() {
        val fixture = conditionalQueryWorld(fromProvider = false, directive = "include")

        val result = resolveAndValidate(fixture, "{ yes: consumer(flag: true) no: consumer(flag: false) }")

        assertEquals(7, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to true))).get())
        assertEquals(0, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to false))).get())
    }

    @Test
    fun `conditional-only Query inputs retain transitive demand with argument skip conditions`() {
        val fixture = conditionalQueryWorld(fromProvider = false, directive = "skip")

        val result = resolveAndValidate(fixture, "{ yes: consumer(flag: true) no: consumer(flag: false) }")

        assertEquals(0, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to true))).get())
        assertEquals(7, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to false))).get())
    }

    @Test
    fun `conditional-only Query inputs retain transitive demand with provider include conditions`() {
        val fixture = conditionalQueryWorld(fromProvider = true, directive = "include")

        val result = resolveAndValidate(fixture, "{ yes: consumer(flag: true) no: consumer(flag: false) }")

        assertEquals(7, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to true))).get())
        assertEquals(0, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to false))).get())
    }

    @Test
    fun `conditional-only Query inputs retain transitive demand with provider skip conditions`() {
        val fixture = conditionalQueryWorld(fromProvider = true, directive = "skip")

        val result = resolveAndValidate(fixture, "{ yes: consumer(flag: true) no: consumer(flag: false) }")

        assertEquals(0, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to true))).get())
        assertEquals(7, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to false))).get())
    }

    private fun conditionalQueryWorld(
        fromProvider: Boolean,
        directive: String,
    ): TestWorld {
        return TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL = "type Query { consumer(flag: Boolean!): Int middle: Int base: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val consumer = fieldResolverOf(
                    objectFragment = schema.emptyFragmentOf("Query"),
                    queryFragment = schemas.fragmentFrom("fragment Input on Query { value: middle @$directive(if: ${'$'}flag) }"),
                ) { _, query, arguments ->
                    val included = (arguments.fieldValues.getValue("flag") as Boolean) == (directive == "include")
                    val values = query.selectionValues()
                    assertEquals(if (included) setOf("value") else emptySet(), values.keys)
                    if (included) values.getValue("value") else 0
                }
                mapOf(
                    schema.requireObjectField("Query", "consumer") to if (fromProvider) {
                        consumer.withVariablesProvider(setOf("flag")) { arguments -> mapOf("flag" to arguments.fieldValues.getValue("flag")) }
                    } else {
                        consumer
                    },
                    schema.requireObjectField("Query", "middle") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom("fragment Input on Query { base }"),
                    ) { _, query, _ -> query.selectionValues().getValue("base") },
                    schema.requireObjectField("Query", "base") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                )
            },
            variableProviders = { schemas ->
                val consumer = schemas.loweredSchema.requireObjectField("Query", "consumer")
                if (fromProvider) emptyMap() else mapOf(Arguments.Variable.of(consumer, "flag") to schemas.loweredSchema.fromArgument(consumer, "flag"))
            },
        )
    }

    @Test
    fun `nested Query conditions preserve static siblings and complementary selections`() {
        val fixture = TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL = "type Query { consumer(flag: Boolean!): Int stable: Int branch: Branch } type Branch { fixed: Int included: Int skipped: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom(
                            "fragment Input on Query { stable branch { fixed included @include(if: ${'$'}flag) skipped @skip(if: ${'$'}flag) } }",
                        ),
                    ) { _, query, arguments ->
                        val values = query.selectionValues()
                        assertEquals(setOf("stable", "branch"), values.keys)
                        val branch = assertIs<EngineObjectData.Sync>(values.getValue("branch")).selectionValues()
                        val conditional = if (arguments.fieldValues.getValue("flag") as Boolean) "included" else "skipped"
                        assertEquals(setOf("fixed", conditional), branch.keys)
                        (values.getValue("stable") as Int) + (branch.getValue("fixed") as Int) + (branch.getValue(conditional) as Int)
                    },
                    schema.requireObjectField("Query", "stable") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 11 },
                    schema.requireObjectField("Query", "branch") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                        schema.objectOf("Branch") {
                            "fixed" setTo 2
                            "included" setTo 3
                            "skipped" setTo 5
                        }
                    },
                )
            },
            variableProviders = { schemas ->
                val consumer = schemas.loweredSchema.requireObjectField("Query", "consumer")
                mapOf(Arguments.Variable.of(consumer, "flag") to schemas.loweredSchema.fromArgument(consumer, "flag"))
            },
        )

        val result = resolveAndValidate(fixture, "{ yes: consumer(flag: true) no: consumer(flag: false) }")

        assertEquals(16, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to true))).get())
        assertEquals(18, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to false))).get())
    }

    @Test
    fun `overlapping Query conditions retain each alias and its transitive input`() {
        val fixture = TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL = "type Query { consumer(left: Boolean!, right: Boolean!): Int stable: Int middle: Int base: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom(
                            "fragment Input on Query { stable left: middle @include(if: ${'$'}left) right: middle @include(if: ${'$'}right) }",
                        ),
                    ) { _, query, arguments ->
                        val expectedKeys = setOf("stable") + listOf("left", "right").filter { arguments.fieldValues.getValue(it) as Boolean }
                        val values = query.selectionValues()
                        assertEquals(expectedKeys, values.keys)
                        values.values.sumOf { it as Int }
                    },
                    schema.requireObjectField("Query", "stable") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 3 },
                    schema.requireObjectField("Query", "middle") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom("fragment Input on Query { base }"),
                    ) { _, query, _ -> query.selectionValues().getValue("base") },
                    schema.requireObjectField("Query", "base") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                )
            },
            variableProviders = { schemas ->
                val consumer = schemas.loweredSchema.requireObjectField("Query", "consumer")
                listOf("left", "right").associate { name -> Arguments.Variable.of(consumer, name) to schemas.loweredSchema.fromArgument(consumer, name) }
            },
        )

        val result =
            resolveAndValidate(
                fixture,
                "{ neither: consumer(left: false, right: false) left: consumer(left: true, right: false) right: consumer(left: false, right: true) both: consumer(left: true, right: true) }"
            )

        for (left in listOf(false, true)) {
            for (right in listOf(false, true)) {
                assertEquals(
                    3 + 7 * listOf(left, right).count { it },
                    result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("left" to left, "right" to right))).get(),
                )
            }
        }
    }

    @Test
    fun `failed Query condition bindings retain static inputs and isolate the owning field`() {
        val staticInputs = AtomicInteger()
        val fixture = TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL = "type Query { consumer: Int stable: Int conditional: Int independent: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom("fragment Input on Query { stable conditional @include(if: ${'$'}flag) }"),
                    ) { _, query, _ -> query.selectionValues().values.sumOf { it as Int } }
                        .withVariablesProvider(setOf("flag")) { error("condition provider failed") },
                    schema.requireObjectField("Query", "stable") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                        staticInputs.incrementAndGet()
                        7
                    },
                    schema.requireObjectField("Query", "conditional") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 11 },
                    schema.requireObjectField("Query", "independent") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 13 },
                )
            },
        )

        val result = resolveAndValidate(fixture, "{ consumer independent }")

        assertIs<ErrorEngineResult>(result.getCell(fixture.schema.contractKey("Query", "consumer")).get())
        assertEquals(13, result.getCell(fixture.schema.contractKey("Query", "independent")).get())
        assertEquals(1, staticInputs.get())
    }

    @Test
    fun `Query materialization retains a conditional alias when construction demand is unconditional`() {
        val fixture = TestWorld.fromSDL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL = "type Query { consumer(flag: Boolean!): Int echo: Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                mapOf(
                    schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                        objectFragment = schema.emptyFragmentOf("Query"),
                        queryFragment = schemas.fragmentFrom("fragment Input on Query { always: echo conditional: echo @include(if: ${'$'}flag) }"),
                    ) { _, query, arguments ->
                        val values = query.selectionValues()
                        assertEquals(arguments.fieldValues.getValue("flag"), "conditional" in values)
                        (values.getValue("always") as Int) + if ("conditional" in values) values.getValue("conditional") as Int else 0
                    },
                    schema.requireObjectField("Query", "echo") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                )
            },
            variableProviders = { schemas ->
                val consumer = schemas.loweredSchema.requireObjectField("Query", "consumer")
                mapOf(Arguments.Variable.of(consumer, "flag") to schemas.loweredSchema.fromArgument(consumer, "flag"))
            },
        )

        val result = resolveAndValidate(fixture, "{ included: consumer(flag: true) excluded: consumer(flag: false) }")

        assertEquals(14, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to true))).get())
        assertEquals(7, result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("flag" to false))).get())
    }

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
        val fixture = TestWorld.fromDSL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL = """
                extend type Query {
                  consumer(seed: Int!): Int
                    @resolver(
                      qf: "middle(value: ${'$'}value)"
                      providerVars: {value: null}
                      result: "value(query:middle)"
                    )
                  middle(value: Int): Int
                    @resolver(qf: "echo(value: ${'$'}value)", result: "value(query:echo)")
                  echo(value: Int): Int @resolver(result: "value(${'$'}value)")
                }
            """.trimIndent(),
        )
        val result = resolveAndValidate(fixture, "{ consumer(seed: 4) }")

        assertNull(result.getCell(fixture.schema.contractKey("Query", "consumer", mapOf("seed" to 4))).get())
    }

    @Test
    fun `failing Query provider isolates its owning field and completes bindings`() {
        val resolution = resolveFailedQueryProvider { error("provider failed") }

        assertIs<ErrorEngineResult>(resolution.value)
        assertEquals(7, resolution.echo)
        assertEquals(VariableBinding.Error, resolution.binding)
    }

    @Test
    fun `cancelled Query provider isolates its owning field and completes bindings`() {
        val resolution = resolveFailedQueryProvider { throw CancellationException("local provider cancellation") }

        assertIs<ErrorEngineResult>(resolution.value)
        assertEquals(7, resolution.echo)
        assertEquals(VariableBinding.Error, resolution.binding)
    }

    @Test
    fun `missing-value Query provider isolates its owning field and completes bindings`() {
        val resolution = resolveFailedQueryProvider { emptyMap() }

        assertIs<ErrorEngineResult>(resolution.value)
        assertEquals(7, resolution.echo)
        assertEquals(VariableBinding.Error, resolution.binding)
    }

    @Test
    fun `extra-value Query provider isolates its owning field and completes bindings`() {
        val resolution = resolveFailedQueryProvider { mapOf("value" to 3, "extra" to 1) }

        assertIs<ErrorEngineResult>(resolution.value)
        assertEquals(7, resolution.echo)
        assertEquals(VariableBinding.Error, resolution.binding)
    }

    @Test
    fun `failing reference target provider retains replayable hop evidence`() {
        val resolution = resolveFailedQueryProvider(referenceTarget = true) { error("provider failed") }

        assertIs<ErrorEngineResult>(resolution.value)
        assertEquals(7, resolution.echo)
        assertEquals(VariableBinding.Error, resolution.binding)
        assertFalse(resolution.targetInvoked)
    }

    @Test
    fun `cancelled reference target provider retains replayable hop evidence`() {
        val resolution = resolveFailedQueryProvider(referenceTarget = true) { throw CancellationException("local provider cancellation") }

        assertIs<ErrorEngineResult>(resolution.value)
        assertEquals(7, resolution.echo)
        assertEquals(VariableBinding.Error, resolution.binding)
        assertFalse(resolution.targetInvoked)
    }

    @Test
    fun `missing-value reference target provider retains replayable hop evidence`() {
        val resolution = resolveFailedQueryProvider(referenceTarget = true) { emptyMap() }

        assertIs<ErrorEngineResult>(resolution.value)
        assertEquals(7, resolution.echo)
        assertEquals(VariableBinding.Error, resolution.binding)
        assertFalse(resolution.targetInvoked)
    }

    @Test
    fun `extra-value reference target provider retains replayable hop evidence`() {
        val resolution = resolveFailedQueryProvider(referenceTarget = true) { mapOf("value" to 3, "extra" to 1) }

        assertIs<ErrorEngineResult>(resolution.value)
        assertEquals(7, resolution.echo)
        assertEquals(VariableBinding.Error, resolution.binding)
        assertFalse(resolution.targetInvoked)
    }

    private fun resolveFailedQueryProvider(
        referenceTarget: Boolean = false,
        provider: VariablesProviderFunction,
    ): QueryProviderFailureResolution {
        val fixture = queryVariablesWorld(provider)
        val fieldName = if (referenceTarget) "reference" else "consumer"
        val key = fixture.schema.contractKey("Query", fieldName, mapOf("seed" to 4))
        val resolution = resolveAndValidateObserved(fixture, "{ $fieldName(seed: 4) echo(value: 7) }")
        val observer = assertIs<CorrectnessResolverObserver>(resolution.operation.resolutionObserver)
        val (targetField, occurrence) = if (referenceTarget) {
            val hop = observer.rootFieldReferenceInvocations().single()
            hop.invocationKey.field to ResolverOccurrenceId.at(hop.invocationRoot, hop.invocationPath)
        } else {
            key.field to ResolverOccurrenceId.at(resolution.result, listOf(key))
        }
        val definitions = fixture.resolverRegistry.resolver(targetField).instantiatedVariableDefinitions(occurrence)

        return QueryProviderFailureResolution(
            value = resolution.result.getCell(key).get(),
            echo = resolution.result.getCell(fixture.schema.contractKey("Query", "echo", mapOf("value" to 7))).get(),
            binding = resolution.operation.variableBindings.getBinding(requireNotNull(definitions.single().variable.instanceId)),
            targetInvoked = occurrence in observer.invokedResolverOccurrences(),
        )
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
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {computed: 42})
                      echo(value: Int): Int
                    }
                    type Item {
                      computed: Int
                        @resolver(
                          qf: "echo(value: ${'$'}value)"
                          providerVars: {value: "ERROR"}
                          result: "ERROR"
                        )
                    }
                    """.trimIndent(),
            )

        val result = resolveAndValidate(fixture, "{ item { computed } }")

        val item = assertIs<ObjectEngineResult>(result.getCell(fixture.schema.contractKey("Query", "item")).get())
        assertEquals(42, item.getCell(fixture.schema.contractKey("Item", "computed")).get())
    }

    @Test
    fun `Query providers bind separately for equal keys in distinct list items`() {
        val providerCalls = AtomicInteger()
        val fixture =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL = "type Query { items: [Item!]!, echo(value: Int): Int } type Item { computed(seed: Int!): Int }",
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(
                        schema.requireObjectField("Query", "items") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                            List(2) { schema.objectOf("Item") }
                        },
                        schema.requireObjectField("Item", "computed") to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Item"),
                                queryFragment = schemas.fragmentFrom("fragment Input on Query { echo(value: ${'$'}value) }"),
                            ) { _, query, _ -> query.selectionValues().getValue("echo") }
                                .withVariablesProvider(setOf("value")) { arguments ->
                                    providerCalls.incrementAndGet()
                                    mapOf("value" to (arguments.fieldValues.getValue("seed") as Int) * 2)
                                },
                        schema.requireObjectField("Query", "echo") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments -> arguments.fieldValues.getValue("value") },
                    )
                },
            )
        val result = resolveAndValidate(fixture, "{ items { computed(seed: 4) } }")
        val items = assertIs<ListEngineResult>(result.getCell(fixture.schema.contractKey("Query", "items")).get())

        assertEquals(
            listOf(8, 8),
            items.map { cell ->
                assertIs<ObjectEngineResult>(cell.get())
                    .getCell(fixture.schema.contractKey("Item", "computed", mapOf("seed" to 4))).get()
            },
        )
        assertEquals(2, providerCalls.get())
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

private data class QueryProviderFailureResolution(
    val value: EngineResult?,
    val echo: EngineResult?,
    val binding: VariableBinding,
    val targetInvoked: Boolean,
)
