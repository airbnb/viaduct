@file:Suppress("ForbiddenImport")

package semantics.contract

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import model.EngineErrorData
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.objectOf
import model.operationSelectionsFrom
import model.outputValue
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.registry.TypeCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.CycleCheckState
import semantics.shared.CycleSlot
import semantics.shared.CycleTask
import semantics.shared.CycleTaskKind
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

/** Grounded, variable-free type-checker fragments composed by Resolver22 and Resolver23. */
interface GroundedTypeCheckerFragmentContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `checked resolver parent input upgrades a checker-only occurrence to type-checked`() {
        listOf(false, true).forEach { checkedParentRead ->
            val typeInvocations = AtomicInteger()
            val world =
                TestWorld.fromDSL(
                    schemaSDL =
                        """
                    extend type Query {
                      checked: Int! @resolver(result: 1)
                      rawRoot: Root! @resolver(result: {id: 7, child: {}})
                    }

                    type Root {
                      id: Int!
                      active: Int! @resolver(of: "child { parent { id } }", result: 1)
                      child: Child!
                    }

                    type Child {
                      parent: Root! @parent
                    }
                        """.trimIndent(),
                    selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                    fieldCheckers = { schema ->
                        val checked = schema.requireObjectField("Query", "checked")
                        val rawRoot =
                            schema
                                .fragmentFrom("fragment RawRoot on Query { rawRoot { ${if (checkedParentRead) "active" else "id"} } }")
                                .materializeSelections
                        mapOf(
                            checked to
                                FieldCheckerResolver.of(
                                    checked,
                                    schema.requireQueryTypeDef(),
                                    fragmentTemplates =
                                        mapOf(
                                            "rawRoot" to
                                                ResolverFragmentTemplates(
                                                    objectFragmentTemplate = rawRoot,
                                                    queryFragmentTemplate = materializeSelectionForestOf(),
                                                ),
                                        ),
                                ) { _, _, _ -> CheckerResult.Success },
                        )
                    },
                    typeCheckers = { schema ->
                        val root = schema.requireType("Root") as ViaductSchema.Object
                        mapOf(
                            root to TypeCheckerResolver.of(root, schema.requireQueryTypeDef()) { _, _ ->
                                typeInvocations.incrementAndGet()
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

            val rawRoot = assertIs<ObjectEngineResult>(result.t3Value(world, "Query", "rawRoot"))
            assertEquals(if (checkedParentRead) 1 else 0, typeInvocations.get())
            assertSame(if (checkedParentRead) CheckerResult.Success else null, rawRoot.typeCheckerResult.get())
        }
    }

    @Test
    fun `checked parent reads inside a Query fragment do not check its Query root`() {
        val queryChecks = AtomicInteger()
        val wrapperChecks = AtomicInteger()
        val world = TestWorld.fromSDL(
            """
            directive @parent on FIELD_DEFINITION
            type Query { consume: Int! wrapper: Wrapper! }
            type Wrapper { token: Int! child: Child! }
            type Child { parent: Wrapper! @parent }
            """.trimIndent(),
            selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
            fieldResolvers = { schema ->
                mapOf(
                    schema.requireObjectField("Query", "consume") to fieldResolverOf(
                        schema.emptyFragmentOf("Query"),
                        schema.fragmentFrom("fragment Input on Query { wrapper { child { parent { token } } } }"),
                    ) { _, query, _ ->
                        val wrapper = assertIs<viaduct.engine.api.EngineObjectData.Sync>(query.get("wrapper"))
                        val child = assertIs<viaduct.engine.api.EngineObjectData.Sync>(wrapper.get("child"))
                        val parent = assertIs<viaduct.engine.api.EngineObjectData.Sync>(child.get("parent"))
                        parent.get("token")
                    },
                    schema.requireObjectField("Query", "wrapper") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                        schema.objectOf("Wrapper") {
                            "token" setTo 7
                            "child" setTo schema.objectOf("Child")
                        }
                    },
                )
            },
            typeCheckers = { schema ->
                val query = schema.requireQueryTypeDef()
                val wrapper = schema.requireType("Wrapper") as ViaductSchema.Object
                mapOf(
                    query to TypeCheckerResolver.of(query, query) { _, _ ->
                        queryChecks.incrementAndGet()
                        CheckerResult.Success
                    },
                    wrapper to TypeCheckerResolver.of(wrapper, query) { _, _ ->
                        wrapperChecks.incrementAndGet()
                        CheckerResult.Success
                    },
                )
            },
        ).assumptions

        val result = coroutineResolverSubject.resolve(
            SharedOperationContext.create(world),
            world.operationSelectionsFrom("{ consume }"),
        )

        assertEquals(7, result.t3Value(world, "Query", "consume"))
        assertEquals(1, queryChecks.get(), "Only the primary checked Query root needs a type check")
        assertEquals(1, wrapperChecks.get(), "The child parent read reuses the wrapper's check")
    }

    @Test
    fun `materializes empty object Query and paired named inputs raw for every occurrence`() {
        val typeCheckerCalls = AtomicInteger()
        val activeResolverCalls = AtomicInteger()
        val requestedCheckerCalls = AtomicInteger()
        val passiveCheckerCalls = AtomicInteger()
        val activeCheckerCalls = AtomicInteger()
        val protectedCheckerCalls = AtomicInteger()
        val queryCheckerCalls = AtomicInteger()
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      first: Item!
                      second: Item!
                      queryDependency: Int!
                      consume: Int!
                    }

                    type Item {
                      requested: Int!
                      passive: Int!
                      active: Int!
                      protected: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val first = schema.requireObjectField("Query", "first")
                    val second = schema.requireObjectField("Query", "second")
                    val queryDependency = schema.requireObjectField("Query", "queryDependency")
                    val consume = schema.requireObjectField("Query", "consume")
                    val active = schema.requireObjectField("Item", "active")
                    val protected = schema.requireObjectField("Item", "protected")
                    mapOf(
                        first to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") {
                                    "requested" setTo 1
                                    "passive" setTo 2
                                }
                            },
                        second to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") {
                                    "requested" setTo 11
                                    "passive" setTo 12
                                }
                            },
                        queryDependency to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 4 },
                        consume to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Input on Query { first { requested } second { requested } }",
                                ),
                            ) { input, _ ->
                                val firstValue = input.outputValue("first")
                                if (firstValue is EngineErrorData) {
                                    firstValue
                                } else {
                                    val secondValue = input.outputValue("second")
                                    if (secondValue is EngineErrorData) secondValue else 1
                                }
                            },
                        active to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Item { protected }"),
                            ) { input, _ ->
                                activeResolverCalls.incrementAndGet()
                                (input.get("protected") as Int) + 10
                            },
                        protected to
                            fieldResolverOf(schema.emptyFragmentOf("Item")) { _, _ -> 3 },
                    )
                },
                fieldCheckers = { schema ->
                    val query = schema.requireQueryTypeDef()
                    mapOf(
                        schema.requireObjectField("Item", "requested") to
                            FieldCheckerResolver.of(
                                schema.requireObjectField("Item", "requested"),
                                query,
                            ) { _, _, _ ->
                                requestedCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                        schema.requireObjectField("Item", "passive") to
                            FieldCheckerResolver.of(
                                schema.requireObjectField("Item", "passive"),
                                query,
                            ) { _, _, _ ->
                                passiveCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                        schema.requireObjectField("Item", "active") to
                            FieldCheckerResolver.of(
                                schema.requireObjectField("Item", "active"),
                                query,
                            ) { _, _, _ ->
                                activeCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                        schema.requireObjectField("Item", "protected") to
                            FieldCheckerResolver.of(
                                schema.requireObjectField("Item", "protected"),
                                query,
                            ) { _, _, _ ->
                                protectedCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                        schema.requireObjectField("Query", "queryDependency") to
                            FieldCheckerResolver.of(
                                schema.requireObjectField("Query", "queryDependency"),
                                query,
                            ) { _, _, _ ->
                                queryCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.requireType("Item") as ViaductSchema.Object

                    fun pair(
                        objectFragment: String? = null,
                        queryFragment: String? = null,
                    ): ResolverFragmentTemplates =
                        ResolverFragmentTemplates(
                            objectFragmentTemplate =
                                objectFragment
                                    ?.let { schema.fragmentFrom(it).materializeSelections }
                                    ?: materializeSelectionForestOf(),
                            queryFragmentTemplate =
                                queryFragment
                                    ?.let { schema.fragmentFrom(it).materializeSelections }
                                    ?: materializeSelectionForestOf(),
                        )
                    mapOf(
                        item to
                            TypeCheckerResolver.of(
                                item,
                                schema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    linkedMapOf(
                                        "empty" to pair(),
                                        "object" to
                                            pair(
                                                objectFragment =
                                                    "fragment ObjectInput on Item { objectValue: passive }",
                                            ),
                                        "query" to
                                            pair(
                                                queryFragment =
                                                    "fragment QueryInput on Query { queryValue: queryDependency }",
                                            ),
                                        "paired" to
                                            pair(
                                                objectFragment =
                                                    "fragment PairedObject on Item { activeValue: active }",
                                                queryFragment =
                                                    "fragment PairedQuery on Query { pairedQueryValue: queryDependency }",
                                            ),
                                    ),
                            ) { inputs, _ ->
                                typeCheckerCalls.incrementAndGet()
                                assertEquals(emptySet(), inputs.getValue("empty").objectValue.getSelections())
                                assertEquals(emptySet(), inputs.getValue("empty").queryValue.getSelections())
                                val passive = inputs.getValue("object").objectValue.get("objectValue") as Int
                                assertTrue(passive == 2 || passive == 12)
                                assertEquals(4, inputs.getValue("query").queryValue.get("queryValue"))
                                assertEquals(13, inputs.getValue("paired").objectValue.get("activeValue"))
                                assertEquals(4, inputs.getValue("paired").queryValue.get("pairedQueryValue"))
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                world.operationSelectionsFrom("{ consume }"),
            )

        val consumeValue = result.t3Value(world, "Query", "consume")
        if (consumeValue is ErrorEngineResult) {
            throw AssertionError("Type-checker input construction failed", consumeValue.errorData.cause)
        }
        assertEquals(1, consumeValue)
        assertEquals(2, typeCheckerCalls.get())
        assertEquals(2, activeResolverCalls.get())
        assertEquals(2, requestedCheckerCalls.get())
        assertEquals(0, passiveCheckerCalls.get())
        assertEquals(0, activeCheckerCalls.get())
        assertEquals(2, protectedCheckerCalls.get())
        assertEquals(0, queryCheckerCalls.get())
    }

    @Test
    fun `Query input materialization failure completes the type-checker slot exceptionally`() {
        val failure = IllegalStateException("Query input materialization failed")
        val checkerInvoked = AtomicBoolean()
        val writers = ConcurrentHashMap<CycleSlot, CycleTask>()
        val cycleChecker =
            object : CycleCheckState {
                override fun registerWriter(
                    slot: CycleSlot,
                    writer: CycleTask,
                ) {
                    writers[slot] = writer
                }

                override fun cycleCheck(
                    reader: CycleTask,
                    slot: CycleSlot,
                ) {
                    val writer = writers.getValue(slot)
                    if (
                        reader.kind == CycleTaskKind.TYPE_CHECKER &&
                        (writer.path.lastOrNull() as? ObjectEngineResult.ObjectKey)?.field?.name ==
                        "queryDependency"
                    ) {
                        throw failure
                    }
                }
            }
        val world =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query { item: Item!, queryDependency: Int! }
                    type Item { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.requireObjectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") { "value" setTo 1 }
                            },
                        schema.requireObjectField("Query", "queryDependency") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 2 },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to
                            TypeCheckerResolver.of(
                                item,
                                schema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "input" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate = materializeSelectionForestOf(),
                                                queryFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Query { queryDependency }",
                                                        ).materializeSelections,
                                            ),
                                    ),
                            ) { _, _ ->
                                checkerInvoked.set(true)
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                world.operationSelectionsFrom("{ item { value } }"),
                cycleChecker,
            )
        val item = assertIs<ObjectEngineResult>(result.t3Value(world, "Query", "item"))

        assertSame(failure, assertFailsWith<IllegalStateException> { item.typeCheckerResult.get() })
        assertEquals(false, checkerInvoked.get())
    }
}

private fun ObjectEngineResult.t3Value(
    world: model.Assumptions,
    typeName: String,
    fieldName: String,
): Any? =
    getCell(
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField(typeName, fieldName),
            emptyMap(),
        ),
    ).value.get()
