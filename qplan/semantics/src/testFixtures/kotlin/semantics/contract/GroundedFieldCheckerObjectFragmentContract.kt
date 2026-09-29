package semantics.contract

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import model.Arguments
import model.ObjectEngineResult
import model.arg
import model.emptyFragmentOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.operationSelectionsFrom
import model.registry.CheckerInput
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverTarget
import model.registry.VariableDefinition
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.ResolverReadCycleException
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.graphqljava.gjDef

/** Grounded object-rooted checker inputs, composed by Resolver22 and Resolver23. */
interface GroundedFieldCheckerObjectFragmentContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `value and checker wait cycle fails instead of hanging`() {
        val world =
            TestWorld.fromSDL(
                schemaSDL = "type Query { checked: Int!, dependency: Int! }",
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldResolvers = { schema ->
                    val checked = schema.requireObjectField("Query", "checked")
                    val dependency = schema.requireObjectField("Query", "dependency")
                    mapOf(
                        checked to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 1 },
                        dependency to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { checked }"),
                            ) { _, _ -> 2 },
                    )
                },
                fieldCheckers = { schema ->
                    val checked = schema.requireObjectField("Query", "checked")
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(
                                checked,
                                schema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "input" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Query { dependency }",
                                                        ).materializeSelections,
                                                queryFragmentTemplate = materializeSelectionForestOf(),
                                            ),
                                    ),
                            ) { _, inputs, _ ->
                                inputs.getValue("input").objectValue.get("dependency")
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                world.operationSelectionsFrom("{ checked }"),
            )
        val failure =
            assertFailsWith<Exception> {
                result
                    .getCell(
                        ObjectEngineResult.GroundKey.of(
                            world.schema.requireObjectField("Query", "checked"),
                            emptyMap(),
                        ),
                    ).fieldCheckerResult
                    .get()
            }

        assertTrue(failure.causeSequence().any { it is ResolverReadCycleException })
    }

    @Test
    fun `materializes named object inputs raw after passive construction`() {
        val inputsSeen = AtomicReference<Map<String, CheckerInput>>()
        val rawActiveChecks = AtomicInteger()
        val rawNestedChecks = AtomicInteger()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {passive: 11, child: {nested: 13}})
                    }

                    type Item {
                      checked(seed: Int!): Int! @resolver(result: 1)
                      active(seed: Int!): Int! @resolver(result: 7)
                      passive: Int!
                      child: Child!
                    }

                    type Child {
                      nested: Int!
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    val query = schema.requireQueryTypeDef()
                    val checked = schema.requireObjectField("Item", "checked")
                    val seed = Arguments.Variable.of(ResolverTarget.FieldCheckerTarget(checked), "seed")
                    val fragments =
                        linkedMapOf(
                            "active" to
                                ResolverFragmentTemplates(
                                    objectFragmentTemplate =
                                        schema
                                            .fragmentFrom(
                                                "fragment Input on Item { active(seed: ${'$'}seed) }",
                                                variableTarget = ResolverTarget.FieldCheckerTarget(checked),
                                            ).materializeSelections,
                                    queryFragmentTemplate = materializeSelectionForestOf(),
                                    variables =
                                        mapOf<Arguments.Variable, VariableDefinition>(
                                            seed to
                                                VariableDefinition.FromArgument.of(
                                                    checkNotNull(checked.arg("seed")),
                                                ),
                                        ),
                                ),
                            "passive" to
                                ResolverFragmentTemplates(
                                    objectFragmentTemplate =
                                        schema
                                            .fragmentFrom("fragment Input on Item { renamed: passive }")
                                            .materializeSelections,
                                    queryFragmentTemplate = materializeSelectionForestOf(),
                                ),
                            "nested" to
                                ResolverFragmentTemplates(
                                    objectFragmentTemplate =
                                        schema
                                            .fragmentFrom("fragment Input on Item { child { nested } }")
                                            .materializeSelections,
                                    queryFragmentTemplate = materializeSelectionForestOf(),
                                ),
                            "empty" to
                                ResolverFragmentTemplates(
                                    objectFragmentTemplate = materializeSelectionForestOf(),
                                    queryFragmentTemplate = materializeSelectionForestOf(),
                                ),
                        )
                    mapOf(
                        checked to
                            FieldCheckerResolver.of(
                                field = checked,
                                queryType = query,
                                fragmentTemplates = fragments,
                            ) { _, inputs, _ ->
                                inputsSeen.set(inputs)
                                CheckerResult.Success
                            },
                        schema.requireObjectField("Item", "active") to
                            FieldCheckerResolver.of(
                                schema.requireObjectField("Item", "active"),
                                query,
                            ) { _, _, _ ->
                                rawActiveChecks.incrementAndGet()
                                CheckerResult.Success
                            },
                        schema.requireObjectField("Child", "nested") to
                            FieldCheckerResolver.of(
                                schema.requireObjectField("Child", "nested"),
                                query,
                            ) { _, _, _ ->
                                rawNestedChecks.incrementAndGet()
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                world.operationSelectionsFrom("{ item { checked(seed: 5) } }"),
            )
        val itemKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "item"),
                emptyMap(),
            )
        val item = assertIs<ObjectEngineResult>(result.getCell(itemKey).value.get())
        val inputs = assertNotNull(inputsSeen.get())

        assertEquals(7, inputs.getValue("active").objectValue.get("active"))
        assertEquals(11, inputs.getValue("passive").objectValue.get("renamed"))
        val child =
            assertIs<EngineObjectData.Sync>(inputs.getValue("nested").objectValue.get("child"))
        assertEquals(13, child.get("nested"))
        val empty = inputs.getValue("empty").objectValue
        assertSame(world.schema.requireType("Item").gjDef, empty.type)
        assertEquals(emptySet(), empty.getSelections())
        inputs.values.forEach { input ->
            assertSame(world.schema.requireQueryTypeDef().gjDef, input.queryValue.type)
            assertEquals(emptySet(), input.queryValue.getSelections())
        }
        assertEquals(0, rawActiveChecks.get())
        assertEquals(0, rawNestedChecks.get())
        val activeKey = item.keys.single { it.field.name == "active" }
        assertNull(item.getCell(activeKey).fieldCheckerResult.get())
    }
}

private fun Throwable.causeSequence(): Sequence<Throwable> = generateSequence(this) { it.cause }
