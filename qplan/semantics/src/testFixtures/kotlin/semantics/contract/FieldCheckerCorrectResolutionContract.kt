package semantics.contract

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import model.Arguments
import model.EngineErrorData
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.arg
import model.emptyFragmentOf
import model.fragmentFrom
import model.objectOf
import model.outputValue
import model.registry.FieldChecker
import model.registry.ResolverFragmentTemplates
import model.registry.VariableDefinition
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.correctresolution.CorrectnessCheckerObserver
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext

/** Access-check evidence consumed by the shared correctness judgment. */
interface FieldCheckerCorrectResolutionContract : ResolverContract {
    @Test
    fun `checker receives raw object and Query inputs under correctness contract`() {
        val checkerCalls = AtomicInteger()
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      source: Int!
                      checked: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "source") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        schema.requireObjectField("Query", "checked") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 11 },
                    )
                },
                fieldCheckers = { schema ->
                    val query = schema.requireQueryTypeDef()
                    val source = schema.requireObjectField("Query", "source")
                    val checked = schema.requireObjectField("Query", "checked")
                    mapOf(
                        source to
                            FieldChecker.of(source, query) { _, _, _ ->
                                error("Raw checker inputs must not invoke the source checker")
                            },
                        checked to
                            FieldChecker.of(
                                field = checked,
                                queryType = query,
                                fragmentTemplates =
                                    mapOf(
                                        "inputs" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Query { objectSource: source }",
                                                        ).materializeSelections,
                                                queryFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Query { querySource: source }",
                                                        ).materializeSelections,
                                            ),
                                    ),
                            ) { _, inputs, _ ->
                                checkerCalls.incrementAndGet()
                                val pair = inputs.getValue("inputs")
                                assertEquals(7, pair.objectValue.outputValue("objectSource"))
                                assertEquals(7, pair.queryValue.outputValue("querySource"))
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        val fragment = world.fragmentFrom("fragment Query on Query { checked }")
        val observation =
            resolveAndValidateObserved(
                world = world,
                root = world.resolverRegistry.createRootQueryInput(),
                selections = fragment.subselections,
            )

        val checkedKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "checked"),
                emptyMap(),
            )
        val checkerOccurrenceId = ResolverOccurrenceId.at(observation.result, listOf(checkedKey))
        assertEquals(
            1,
            (observation.operation.checkerObserver as CorrectnessCheckerObserver)
                .queryFragmentResults(checkerOccurrenceId)
                .size,
        )
        assertEquals(1, checkerCalls.get())
        assertTrue(observation.result.correctResolution(observation.operation, fragment))
        assertEquals(2, checkerCalls.get())
    }

    @Test
    fun `checker replay retains nested occurrence arguments and fromArgument bindings`() {
        val checkerCalls = AtomicInteger()
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      item: Item!
                    }

                    type Item {
                      dependency(value: Int!): Int!
                      checked(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val item = schema.requireObjectField("Query", "item")
                    val dependency = schema.requireObjectField("Item", "dependency")
                    val checked = schema.requireObjectField("Item", "checked")
                    mapOf(
                        item to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item")
                            },
                        dependency to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        checked to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ -> 11 },
                    )
                },
                fieldCheckers = { schema ->
                    val checked = schema.requireObjectField("Item", "checked")
                    val value = Arguments.Variable.of(checked, "value")
                    mapOf(
                        checked to
                            FieldChecker.of(
                                field = checked,
                                queryType = schema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "input" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Item { dependency(value: ${'$'}value) }",
                                                            variableField = checked,
                                                        ).materializeSelections,
                                                queryFragmentTemplate = schema.emptyFragmentOf("Query").materializeSelections,
                                                variables =
                                                    mapOf(
                                                        value to
                                                            VariableDefinition.FromArgument.of(
                                                                checkNotNull(checked.arg("value")),
                                                            ),
                                                    ),
                                            ),
                                    ),
                            ) { arguments, inputs, _ ->
                                checkerCalls.incrementAndGet()
                                val expected = arguments.fieldValues.getValue("value")
                                assertEquals(expected, inputs.getValue("input").objectValue.get("dependency"))
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions
        val fragment = world.fragmentFrom("fragment Query on Query { item { checked(value: 7) } }")
        val observation =
            resolveAndValidateObserved(
                world = world,
                root = world.resolverRegistry.createRootQueryInput(),
                selections = fragment.subselections,
            )

        assertEquals(1, checkerCalls.get())
        assertTrue(observation.result.correctResolution(observation.operation, fragment))
        assertEquals(2, checkerCalls.get())
    }

    @Test
    fun `correct resolution validates access errors in both resolver inputs`() {
        val denial = CorrectResolutionDenial()
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      objectDenied: Int!
                      queryDenied: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val empty = schema.emptyFragmentOf("Query")
                    mapOf(
                        schema.requireObjectField("Query", "objectDenied") to
                            fieldResolverOf(empty) { _, _ -> 1 },
                        schema.requireObjectField("Query", "queryDenied") to
                            fieldResolverOf(empty) { _, _ -> 2 },
                        schema.requireObjectField("Query", "consumer") to
                            fieldResolverOf(
                                objectFragment =
                                    schema.fragmentFrom(
                                        "fragment Input on Query { objectFailure: objectDenied }",
                                    ),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment Input on Query { queryFailure: queryDenied }",
                                    ),
                            ) { input, queryValue, _ ->
                                assertSame(
                                    denial.error,
                                    assertIs<EngineErrorData>(
                                        input.outputValue("objectFailure"),
                                    ).cause,
                                )
                                assertSame(
                                    denial.error,
                                    assertIs<EngineErrorData>(
                                        queryValue.outputValue("queryFailure"),
                                    ).cause,
                                )
                                3
                            },
                    )
                },
                fieldCheckers = { schema ->
                    listOf("objectDenied", "queryDenied").associate { name ->
                        val field = schema.requireObjectField("Query", name)
                        field to
                            FieldChecker.of(field, schema.requireQueryTypeDef()) { _, _, _ ->
                                denial
                            }
                    }
                },
            ).assumptions
        val fragment = world.fragmentFrom("fragment Query on Query { consumer }")
        val observation =
            resolveAndValidateObserved(
                world = world,
                root = world.resolverRegistry.createRootQueryInput(),
                selections = fragment.subselections,
            )

        val resolverObservation =
            (observation.operation.resolverObserver as CorrectnessResolverObserver)
                .allResolverInvocations()
                .single { invocation -> invocation.field.name == "consumer" }
        assertSame(
            denial.error,
            assertIs<EngineErrorData>(
                resolverObservation.input.outputValue("objectFailure"),
            ).cause,
        )
        assertSame(
            denial.error,
            assertIs<EngineErrorData>(
                resolverObservation.queryValue.outputValue("queryFailure"),
            ).cause,
        )
        assertTrue(observation.result.correctResolution(observation.operation, fragment))
    }
}

private class CorrectResolutionDenial : CheckerResult.Error {
    override val error = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
