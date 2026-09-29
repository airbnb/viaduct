@file:Suppress("ForbiddenImport")

package semantics.contract

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import model.ListEngineResult
import model.ObjectEngineResult
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.operationSelectionsFrom
import model.registry.CheckerInput
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.registry.TypeCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import semantics.shared.CheckerKind
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

/** Fragment-free field- and type-checker capability boundary. */
interface FragmentFreeCheckerProfileContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `type checker runs once for every demanded object occurrence`() {
        val invocations = AtomicInteger()
        val recorder = CheckerApplicationRecorder()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      singular: Item! @resolver(result: {id: 1})
                      repeated: [Item!]! @resolver(result: [{id: 2}, {id: 2}])
                      nested: [[Item!]!]! @resolver(result: [[{id: 3}], [{id: 4}]])
                      missing: Item @resolver(result: null)
                      failed: Item @resolver(result: "ERROR")
                      scalar: Int! @resolver(result: 5)
                    }

                    type Item {
                      id: Int!
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                typeCheckers = { schema ->
                    val item = schema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to
                            TypeCheckerResolver.of(item, schema.requireQueryTypeDef()) { inputs, _ ->
                                assertTrue(inputs.isEmpty())
                                invocations.incrementAndGet()
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world, checkerObserver = recorder),
                world.operationSelectionsFrom(
                    "{ singular { id } repeated { id } nested { id } missing { id } failed { id } scalar }",
                ),
            )
        val singular = result.profileObjectValue("singular")
        val repeated = assertIs<ListEngineResult>(result.profileRawValue("repeated"))
        val repeatedFirst = assertIs<ObjectEngineResult>(repeated[0].value.get())
        val repeatedSecond = assertIs<ObjectEngineResult>(repeated[1].value.get())
        val nested = assertIs<ListEngineResult>(result.profileRawValue("nested"))
        val nestedFirst = assertIs<ListEngineResult>(nested[0].value.get())
        val nestedSecond = assertIs<ListEngineResult>(nested[1].value.get())
        val objects =
            listOf(
                singular,
                repeatedFirst,
                repeatedSecond,
                assertIs<ObjectEngineResult>(nestedFirst[0].value.get()),
                assertIs<ObjectEngineResult>(nestedSecond[0].value.get()),
            )

        assertEquals(5, invocations.get())
        assertTrue(result.typeCheckerResult.isCompleted)
        assertNull(result.typeCheckerResult.get())
        assertNotSame(repeatedFirst, repeatedSecond)
        objects.forEach { occurrence ->
            assertSame(CheckerResult.Success, occurrence.typeCheckerResult.get())
        }
        val observations = recorder.checkerApplications()
        assertEquals(5, observations.size)
        assertTrue(observations.all { it.checkerKind == CheckerKind.TYPE })
        assertTrue(observations.all { it.arguments == null && it.checkedType.name == "Item" })
        assertEquals(5, observations.map { it.occurrencePath }.distinct().size)
    }

    @Test
    fun `field checker rejects object and Query required selections`() {
        for (queryRooted in listOf(false, true)) {
            val world =
                TestWorld.fromDSL(
                    schemaSDL =
                        """
                        extend type Query {
                          checked: Int! @resolver(result: 1)
                        }
                        """.trimIndent(),
                    selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                    fieldCheckers = { schema ->
                        val query = schema.requireQueryTypeDef()
                        val checked = schema.requireObjectField("Query", "checked")
                        val fragment =
                            schema.fragmentFrom("fragment Input on Query { checked }").materializeSelections
                        val fragmentTemplates =
                            ResolverFragmentTemplates(
                                objectFragmentTemplate =
                                    if (queryRooted) materializeSelectionForestOf() else fragment,
                                queryFragmentTemplate =
                                    if (queryRooted) fragment else materializeSelectionForestOf(),
                            )
                        mapOf(
                            checked to
                                FieldCheckerResolver.of(
                                    checked,
                                    query,
                                    fragmentTemplates = mapOf("input" to fragmentTemplates),
                                ) { _, _, _ -> CheckerResult.Success },
                        )
                    },
                ).assumptions

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    coroutineResolverSubject.resolve(
                        SharedOperationContext.create(world),
                        world.operationSelectionsFrom("{ checked }"),
                    )
                }
            assertTrue(failure.message.orEmpty().contains("cannot declare"))
        }
    }

    @Test
    fun `type checker rejects object and Query required selections`() {
        for (queryRooted in listOf(false, true)) {
            val world =
                TestWorld.fromDSL(
                    schemaSDL =
                        """
                        extend type Query {
                          item: Item! @resolver(result: {id: 1})
                        }

                        type Item { id: Int! }
                        """.trimIndent(),
                    selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                    typeCheckers = { schema ->
                        val item = schema.requireType("Item") as ViaductSchema.Object
                        val itemFragment =
                            schema.fragmentFrom("fragment ItemInput on Item { id }").materializeSelections
                        val queryFragment =
                            schema
                                .fragmentFrom("fragment QueryInput on Query { item { id } }")
                                .materializeSelections
                        mapOf(
                            item to
                                TypeCheckerResolver.of(
                                    item,
                                    schema.requireQueryTypeDef(),
                                    fragmentTemplates =
                                        mapOf(
                                            "input" to
                                                ResolverFragmentTemplates(
                                                    objectFragmentTemplate =
                                                        if (queryRooted) {
                                                            materializeSelectionForestOf()
                                                        } else {
                                                            itemFragment
                                                        },
                                                    queryFragmentTemplate =
                                                        if (queryRooted) {
                                                            queryFragment
                                                        } else {
                                                            materializeSelectionForestOf()
                                                        },
                                                ),
                                        ),
                                ) { _: Map<String, CheckerInput>, _ -> CheckerResult.Success },
                        )
                    },
                ).assumptions

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    coroutineResolverSubject.resolve(
                        SharedOperationContext.create(world),
                        world.operationSelectionsFrom("{ item { id } }"),
                    )
                }
            assertTrue(failure.message.orEmpty().contains("cannot declare"))
        }
    }
}

private fun ObjectEngineResult.profileRawValue(fieldName: String): Any? {
    val field = type.fields.single { it.name == fieldName }
    return getCell(ObjectEngineResult.GroundKey.of(field, emptyMap())).value.get()
}

private fun ObjectEngineResult.profileObjectValue(fieldName: String): ObjectEngineResult = assertIs<ObjectEngineResult>(profileRawValue(fieldName))
