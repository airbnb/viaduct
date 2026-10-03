@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Assumptions
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.ResolverTarget
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolverObserver
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

/** Exact producer demand and per-OER applications, independently of completed-value replay. */
interface SelectiveTypeCheckerExactnessContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `selective producer receives object but not Query type-checker demand`() {
        val producerDemand = AtomicReference<SelectionForest>()
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      item: Item!
                      queryPolicy: Int!
                    }

                    type Item {
                      visible: Int!
                      objectPolicy: Int!
                      queryPolicy: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "item") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Item") {
                                    "visible" setTo 1
                                    "objectPolicy" setTo 2
                                    "queryPolicy" setTo 999
                                }
                            },
                        schema.loweredSchema.requireObjectField("Query", "queryPolicy") to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 3 },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to
                            TypeCheckerResolver.of(
                                item,
                                schema.loweredSchema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "input" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment ObjectInput on Item { objectPolicy }",
                                                        ).materializeSelections,
                                                queryFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment QueryInput on Query { queryPolicy }",
                                                        ).materializeSelections,
                                            ),
                                    ),
                            ) { inputs, _ ->
                                assertEquals(2, inputs.getValue("input").objectValue.outputValue("objectPolicy"))
                                assertEquals(3, inputs.getValue("input").queryValue.outputValue("queryPolicy"))
                                CheckerResult.Success
                            },
                    )
                },
            )
        val world = worldFixture.assumptions
        val observer =
            object : ResolverObserver {
                override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                    if (observation.field.name == "item") producerDemand.set(observation.suppliedDemand)
                }
            }

        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world, resolverObserver = observer),
            worldFixture.schemas.operationSelectionsFrom("{ item { visible } }"),
        )

        val item = worldFixture.schema.requireType("Item") as ViaductSchema.Object
        assertEquals(
            setOf("visible", "objectPolicy"),
            assertNotNull(producerDemand.get()).merge(item).groundKeys().mapTo(linkedSetOf()) { it.field.name },
        )
    }

    @Test
    fun `type checker raw demand restores checked inputs only beyond active boundaries`() {
        listOf(false, true).forEach { selectRaw ->
            val worldFixture =
                TestWorld.fromSDL(
                    schemaSDL = """
                    type Query { item: Item! }
                    type Item { value: Int! raw: Raw! unused: Raw! }
                    type Raw { token: Int! active: Int! dependency: Dependency! extra: Int! }
                    type Dependency { value: Int! policy: Int! }
                    """.trimIndent(),
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.loweredSchema.requireObjectField("Query", "item") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Item") {
                                    "value" setTo 1
                                    "raw" setTo schema.loweredSchema.objectOf("Raw") {
                                        "token" setTo 2
                                        "dependency" setTo schema.loweredSchema.objectOf("Dependency") {
                                            "value" setTo 3
                                            "policy" setTo 4
                                        }
                                        "extra" setTo 99
                                    }
                                    "unused" setTo schema.loweredSchema.objectOf("Raw") { "token" setTo 100 }
                                }
                            },
                            schema.loweredSchema.requireObjectField("Raw", "active") to fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Raw { dependency { value } }"),
                            ) { input, _ -> (input.get("dependency") as viaduct.engine.api.EngineObjectData.Sync).get("value") },
                        )
                    },
                    typeCheckers = { schema ->
                        listOf("Item", "Raw", "Dependency").associate { name ->
                            val type = schema.loweredSchema.requireType(name) as ViaductSchema.Object
                            val objectSelections = when (name) {
                                "Item" -> "raw { token active }"
                                "Raw" -> "token"
                                else -> "policy"
                            }
                            type to TypeCheckerResolver.of(type, schema.loweredSchema.requireQueryTypeDef(), mapOf("input" to schema.t4Pair(name, objectSelections))) { inputs, _ ->
                                val input = inputs.getValue("input").objectValue
                                assertTrue(input.getSelections().iterator().hasNext())
                                if (name == "Item") {
                                    val raw = input.get("raw") as viaduct.engine.api.EngineObjectData.Sync
                                    assertEquals(2, raw.get("token"))
                                    assertEquals(3, raw.get("active"))
                                }
                                if (name == "Raw") TypeExactnessDenial() else CheckerResult.Success
                            }
                        }
                    },
                    fieldCheckers = { schema ->
                        listOf("Item" to "raw", "Raw" to "token", "Raw" to "active", "Raw" to "dependency", "Dependency" to "value", "Dependency" to "policy").associate { (type, name) ->
                            val field = schema.loweredSchema.requireObjectField(type, name)
                            field to FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success }
                        }
                    },
                )
            val world = worldFixture.assumptions
            val run = resolveTypeExactness(worldFixture, if (selectRaw) "{ item { value raw { token } } }" else "{ item { value } }")
            val itemKey = world.t4Key("Query", "item")
            val rawKey = world.t4Key("Item", "raw")
            val dependencyKey = world.t4Key("Raw", "dependency")
            val item = assertIs<ObjectEngineResult>(run.result.getCell(itemKey).value.get())
            val raw = assertIs<ObjectEngineResult>(item.getCell(rawKey).value.get())
            assertEquals(setOf("value", "raw"), item.keys.map { it.field.name }.toSet())
            assertEquals(setOf("token", "active", "dependency"), raw.keys.map { it.field.name }.toSet())
            val supplied = run.invocations.single { it.field == itemKey.field }.suppliedDemand!!
            assertEquals(
                setOf("value", "raw", "raw.token", "raw.active", "raw.dependency", "raw.dependency.value", "raw.dependency.policy"),
                supplied.t4Paths(),
            )
            val expected = buildList {
                add(run.type("Item", listOf(itemKey)))
                add(run.type("Dependency", listOf(itemKey, rawKey, dependencyKey)))
                add(run.field(listOf(itemKey, rawKey, dependencyKey)))
                add(run.field(listOf(itemKey, rawKey, dependencyKey, world.t4Key("Dependency", "value"))))
                if (selectRaw) {
                    add(run.type("Raw", listOf(itemKey, rawKey)))
                    add(run.field(listOf(itemKey, rawKey)))
                    add(run.field(listOf(itemKey, rawKey, world.t4Key("Raw", "token"))))
                } else {
                    assertNull(raw.typeCheckerResult.get())
                }
            }
            run.validate(expected)
        }
    }

    @Test
    fun `abstract nested lists check each concrete occurrence including empty selected subtrees`() {
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL = """
                type Query { items: [[Entry]] }
                union Entry = Item | Other
                type Item { value: Int! policy: Int! extra: Int! }
                type Other { value: Int! policy: Int! }
                """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "items") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            val item = schema.loweredSchema.objectOf("Item") {
                                "value" setTo 1
                                "policy" setTo 7
                                "extra" setTo 99
                            }
                            listOf(
                                listOf(item, null, item),
                                null,
                                listOf(
                                    schema.loweredSchema.objectOf("Other") {
                                        "value" setTo 2
                                        "policy" setTo 8
                                    }
                                )
                            )
                        },
                    )
                },
                typeCheckers = { schema ->
                    listOf("Item", "Other").associate { name ->
                        val type = schema.loweredSchema.requireType(name) as ViaductSchema.Object
                        type to TypeCheckerResolver.of(type, schema.loweredSchema.requireQueryTypeDef(), mapOf("policy" to schema.t4Pair(name, "policy"))) { inputs, _ ->
                            assertEquals(if (name == "Item") 7 else 8, inputs.getValue("policy").objectValue.get("policy"))
                            CheckerResult.Success
                        }
                    }
                },
            )
        val world = worldFixture.assumptions
        val run = resolveTypeExactness(worldFixture, "{ items { ... on Other { value } } }")
        val items = world.t4Key("Query", "items")
        val outer = assertIs<ListEngineResult>(run.result.getCell(items).value.get())
        val inner = assertIs<ListEngineResult>(outer[0].value.get())
        val first = assertIs<ObjectEngineResult>(inner[0].value.get())
        val second = assertIs<ObjectEngineResult>(inner[2].value.get())
        assertNotSame(first, second)
        assertEquals(setOf("policy"), first.keys.map { it.field.name }.toSet())
        run.validate(
            listOf(
                run.type("Item", listOf(items, ListEngineResult.Index.of(0), ListEngineResult.Index.of(0))),
                run.type("Item", listOf(items, ListEngineResult.Index.of(0), ListEngineResult.Index.of(2))),
                run.type("Other", listOf(items, ListEngineResult.Index.of(2), ListEngineResult.Index.of(0))),
            )
        )
    }

    @Test
    fun `field and type owners at the same path retain distinct shared Query scopes`() {
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL = """
                type Query { item(id: Int!): Item! marker: Int! }
                type Item { value: Int! read: Int! }
                """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField(
                            "Query",
                            "item"
                        ) to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> schema.loweredSchema.objectOf("Item") { "value" setTo 1 } },
                        schema.loweredSchema.requireObjectField("Query", "marker") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        schema.loweredSchema.requireObjectField(
                            "Item",
                            "read"
                        ) to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Item"), schema.fragmentFrom("fragment Input on Query { marker }")) { _, query, _ -> query.get("marker") },
                    )
                },
                fieldCheckers = { schema ->
                    listOf("Query" to "item", "Item" to "value").associate { (name, fieldName) ->
                        val field = schema.loweredSchema.requireObjectField(name, fieldName)
                        field to FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef(), mapOf("input" to schema.t4Pair(name, query = "alias: marker"))) { _, inputs, _ ->
                            assertEquals(7, inputs.getValue("input").queryValue.get("alias"))
                            CheckerResult.Success
                        }
                    }
                },
                typeCheckers = { schema ->
                    listOf("Query", "Item").associate { name ->
                        val type = schema.loweredSchema.requireType(name) as ViaductSchema.Object
                        type to TypeCheckerResolver.of(
                            type,
                            schema.loweredSchema.requireQueryTypeDef(),
                            if (name == "Query") emptyMap() else mapOf("input" to schema.t4Pair(name, query = "marker"))
                        ) { _, _ -> CheckerResult.Success }
                    }
                },
            )
        val world = worldFixture.assumptions
        val run = resolveTypeExactness(worldFixture, "{ first: item(id: 1) { value read } second: item(id: 2) { value read } }")
        val queryRoots = (1..2).map { id ->
            val key = world.t4Key("Query", "item", mapOf("id" to id))
            val childId = ResolverOccurrenceId.at(run.result, listOf(key))
            val fieldQuery = run.checkerObserver.queryFragmentResults(ResolverTarget.FieldCheckerTarget(key.field), childId).single()
            val typeQuery = run.checkerObserver.queryFragmentResults(ResolverTarget.TypeCheckerTarget(world.schema.requireType("Item") as ViaductSchema.Object), childId).single()
            val valueId = ResolverOccurrenceId.at(run.result, listOf(key, world.t4Key("Item", "value")))
            assertSame(typeQuery, run.checkerObserver.queryFragmentResults(ResolverTarget.FieldCheckerTarget(world.schema.requireObjectField("Item", "value")), valueId).single())
            assertNotSame(fieldQuery, typeQuery)
            assertNotSame(run.result, typeQuery)
            fieldQuery to typeQuery
        }
        assertSame(queryRoots[0].first, queryRoots[1].first)
        assertNotSame(queryRoots[0].second, queryRoots[1].second)
        assertEquals(3, run.invocations.count { it.field.name == "marker" })
        run.validate(
            buildList {
                add(run.type("Query", emptyList()))
                for (id in 1..2) {
                    val key = world.t4Key("Query", "item", mapOf("id" to id))
                    add(run.field(listOf(key)))
                    add(run.type("Item", listOf(key)))
                    add(run.field(listOf(key, world.t4Key("Item", "value"))))
                }
            }
        )
    }

    @Test
    fun `type checker parent input lifts ancestor demand through nested list occurrences`() {
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL = """
                directive @parent on FIELD_DEFINITION
                type Query { root: Root! marker: Int! }
                type Root { children: [[Child!]!]! secret: Int! }
                type Child { parent: Root! @parent value: Int! }
                """.trimIndent(),
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "root") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            schema.loweredSchema.objectOf("Root") {
                                "secret" setTo 7
                                "children" setTo listOf(listOf(schema.loweredSchema.objectOf("Child") { "value" setTo 1 }, schema.loweredSchema.objectOf("Child") { "value" setTo 1 }))
                            }
                        },
                        schema.loweredSchema.requireObjectField("Query", "marker") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 9 },
                    )
                },
                typeCheckers = { schema ->
                    listOf("Root", "Child").associate { name ->
                        val type = schema.loweredSchema.requireType(name) as ViaductSchema.Object
                        type to TypeCheckerResolver.of(
                            type,
                            schema.loweredSchema.requireQueryTypeDef(),
                            mapOf("input" to schema.t4Pair(name, if (name == "Child") "parent { secret }" else "", "marker"))
                        ) { inputs, _ ->
                            assertEquals(9, inputs.getValue("input").queryValue.get("marker"))
                            if (name == "Child") assertEquals(7, (inputs.getValue("input").objectValue.get("parent") as viaduct.engine.api.EngineObjectData.Sync).get("secret"))
                            CheckerResult.Success
                        }
                    }
                },
            )
        val world = worldFixture.assumptions
        listOf("{ root { children { value } } }", "{ root { children { value parent { secret } } } }").forEach { query ->
            val run = resolveTypeExactness(worldFixture, query)
            val rootKey = world.t4Key("Query", "root")
            val children = world.t4Key("Root", "children")
            run.validate(
                listOf(
                    run.type("Root", listOf(rootKey)),
                    run.type("Child", listOf(rootKey, children, ListEngineResult.Index.of(0), ListEngineResult.Index.of(0))),
                    run.type("Child", listOf(rootKey, children, ListEngineResult.Index.of(0), ListEngineResult.Index.of(1))),
                )
            )
        }
    }

    @Test
    fun `reference targets receive type checker demand and keep publication occurrences distinct`() {
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL = """
                type Query { container: Container! lookup(id: Int!): Item! marker: Int! }
                type Container { items: [Item!]! }
                type Item { value: Int! policy: Int! }
                """.trimIndent(),
                fieldResolvers = { schema ->
                    val lookup = schema.loweredSchema.requireObjectField("Query", "lookup")
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "container") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            schema.loweredSchema.objectOf("Container") { "items" setTo List(2) { RootFieldReferenceData.of(listOf(lookup), mapOf("id" to 1)) } }
                        },
                        lookup to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query"), schema.fragmentFrom("fragment Input on Query { marker }")) { _, _, _ ->
                            schema.loweredSchema.objectOf("Item") {
                                "value" setTo 1
                                "policy" setTo 7
                            }
                        },
                        schema.loweredSchema.requireObjectField("Query", "marker") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 9 },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef(), mapOf("input" to schema.t4Pair("Item", "policy", "marker"))) { inputs, _ ->
                            assertEquals(7, inputs.getValue("input").objectValue.get("policy"))
                            assertEquals(9, inputs.getValue("input").queryValue.get("marker"))
                            CheckerResult.Success
                        }
                    )
                },
            )
        val world = worldFixture.assumptions
        val run = resolveTypeExactness(worldFixture, "{ container { items { value } } }")
        val prefix = listOf(world.t4Key("Query", "container"), world.t4Key("Container", "items"))
        val targets = run.invocations.filter { it.field.name == "lookup" }
        assertEquals(2, targets.size)
        assertNotEquals(targets[0].resolverOccurrenceId, targets[1].resolverOccurrenceId)
        targets.forEach { invocation ->
            assertEquals(setOf("value", "policy"), invocation.suppliedDemand!!.merge(world.schema.requireType("Item") as ViaductSchema.Object).byKey().keys.map { it.field.name }.toSet())
        }
        assertEquals(4, run.invocations.count { it.field.name == "marker" })
        run.validate((0..1).map { run.type("Item", prefix + ListEngineResult.Index.of(it)) })
    }

    @Test
    fun `correctness replay preserves early field denial before a later type denial`() {
        val consumerEntered = CompletableDeferred<Unit>()
        val fieldDenial = TypeExactnessDenial()
        val typeDenial = TypeExactnessDenial()
        val worldFixture =
            TestWorld.fromSDL(
                schemaSDL = "type Query { item: Item! consume: Int! } type Item { value: Int! policy: Int! }",
                fieldResolvers = { schema ->
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "item") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            consumerEntered.await()
                            schema.loweredSchema.objectOf("Item") {
                                "value" setTo 1
                                "policy" setTo 7
                            }
                        },
                        schema.loweredSchema.requireObjectField("Query", "consume") to fieldResolverOf(schema.fragmentFrom("fragment Input on Query { alias: item { value } }")) { input, _ ->
                            consumerEntered.complete(Unit)
                            val error = input.outputValue("alias") as EngineErrorData
                            when (error.cause) {
                                fieldDenial.error -> 1
                                typeDenial.error -> 2
                                else -> 3
                            }
                        },
                    )
                },
                fieldCheckers = { schema ->
                    val item = schema.loweredSchema.requireObjectField("Query", "item")
                    mapOf(item to FieldCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ -> fieldDenial })
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef(), mapOf("input" to schema.t4Pair("Item", "policy"))) { inputs, _ ->
                            assertEquals(7, inputs.getValue("input").objectValue.get("policy"))
                            typeDenial
                        }
                    )
                },
            )
        val world = worldFixture.assumptions
        val run = resolveTypeExactness(worldFixture, "{ consume }")
        val itemKey = world.t4Key("Query", "item")
        assertEquals(1, run.result.getCell(world.t4Key("Query", "consume")).value.get())
        run.validate(listOf(run.field(listOf(itemKey)), run.type("Item", listOf(itemKey))))
    }

    private fun resolveTypeExactness(
        world: TestWorld,
        query: String
    ): TypeExactnessRun {
        val recorder = CheckerApplicationRecorder()
        val checkerObserver = CorrectnessCheckerObserver(recorder)
        val invocations = CopyOnWriteArrayList<ResolverInvocationObservation>()
        val observer = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                invocations += observation
            }
        }
        val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer, checkerObserver = checkerObserver)
        val selections = world.schemas.operationSelectionsFrom(query)
        val result = coroutineResolverSubject.resolve(operation, selections)
        return TypeExactnessRun(operation, selections, result, recorder, checkerObserver, invocations)
    }
}

private class TypeExactnessRun(
    val operation: SharedOperationContext<*>,
    val selections: SelectionForest,
    val result: ObjectEngineResult,
    val recorder: CheckerApplicationRecorder,
    val checkerObserver: CorrectnessCheckerObserver,
    val invocations: List<ResolverInvocationObservation>,
) {
    fun type(
        name: String,
        path: List<PathComponent>
    ): CheckerInvocationObservation =
        CheckerInvocationObservation(
            CheckerKind.TYPE,
            result,
            path,
            null,
            ResolverTarget.TypeCheckerTarget(operation.world.schema.requireType(name) as ViaductSchema.Object),
        )

    fun field(path: List<PathComponent>): CheckerInvocationObservation {
        val key = path.last() as ObjectEngineResult.GroundKey
        return CheckerInvocationObservation(CheckerKind.FIELD, result, path, key.arguments as Arguments.Resolved, ResolverTarget.FieldCheckerTarget(key.field))
    }

    fun validate(expected: List<CheckerInvocationObservation>) {
        assertTrue(recorder.hasExactlyCheckerApplications(expected), "Expected $expected, observed ${recorder.checkerApplications()}")
        assertTrue(recorder.hasExactlyCheckerApplications(result.registeredCheckerApplications(operation)))
        assertEquals(
            expected.filter { it.checkerKind == CheckerKind.TYPE }.toSet(),
            result.demandedTypeCheckerApplications(operation, selections),
        )
        assertFalse(recorder.hasExactlyCheckerApplications(expected.dropLast(1)))
        assertFalse(recorder.hasExactlyCheckerApplications(expected + expected.first()))
        assertTrue(result.correctResolution(operation, selections.merge(operation.world.schema.requireQueryTypeDef())))
        assertTrue(recorder.hasExactlyCheckerApplications(expected), "Relation replay must not emit invocation observations")
    }
}

private fun Assumptions.t4Key(
    type: String,
    field: String,
    arguments: Map<String, Any?> = emptyMap()
): ObjectEngineResult.GroundKey = ObjectEngineResult.GroundKey.of(schema.requireObjectField(type, field), arguments)

private fun ViaductAndGJSchema.t4Pair(
    type: String,
    objectInput: String = "",
    query: String = ""
): ResolverFragmentTemplates =
    ResolverFragmentTemplates(
        if (objectInput.isEmpty()) materializeSelectionForestOf() else fragmentFrom("fragment Input on $type { $objectInput }").materializeSelections,
        if (query.isEmpty()) materializeSelectionForestOf() else fragmentFrom("fragment Input on Query { $query }").materializeSelections,
    )

private fun SelectionForest.t4Paths(prefix: String = ""): Set<String> =
    buildSet {
        this@t4Paths.forEach { selection ->
            val path = prefix + selection.key.field.name
            add(path)
            addAll(selection.subselections.t4Paths("$path."))
        }
    }

private class TypeExactnessDenial : CheckerResult.Error {
    override val error = IllegalStateException("raw object denied to checked consumers")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
