@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.contract.CheckerApplicationRecorder
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.MutationOrchestrationTask
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolutionTask
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver06.resolve as resolve06
import viaduct.engine.runtime2.resolvers.resolver07.resolve as resolve07
import viaduct.engine.runtime2.resolvers.resolver08.resolve as resolve08
import viaduct.engine.runtime2.resolvers.resolver21.resolve as resolve21
import viaduct.engine.runtime2.resolvers.resolver22.resolve as resolve22
import viaduct.engine.runtime2.resolvers.resolver23.resolve as resolve23
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

class MutationResolutionTest : ResolutionDispatcherResource {
    @Test
    fun `inactive payload dependencies do not prevent the next mutation`() {
        val fixture = TestWorld.fromDSL(
            """
            type Mutation { update: Payload @resolver(script: "{:}") }
            type Payload {
                value: Int @resolver(of: "dependency @include(if: ${'$'}enabled)", vars: {names: ["enabled"], script: "{'enabled': false}"}, script: "7")
                dependency: Int @resolver(script: "99")
            }
            """.trimIndent(),
        )
        val result = SharedOperationContext.create(fixture.assumptions).resolveWithTestDispatcher(
            fixture.schemas.operationSelectionsFrom("mutation { first: update { value } second: update { value } }"),
        )
        assertEquals(2, result.keys.size)
        result.keys.forEach { key ->
            val payload = assertInstanceOf(ObjectEngineResult::class.java, result.getCell(key).value.get())
            val valueKey = ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Payload", "value"), emptyMap())
            assertEquals(7, payload.getCell(valueKey).value.get())
        }
    }

    private data class Family(
        val name: String,
        val selective: Boolean,
        val precomputed: Boolean = false,
        val supportsObjectFragments: Boolean = true,
        val resolve: (SharedOperationContext<*>, SelectionForest) -> ObjectEngineResult,
    )

    private val families = listOf(
        Family("06", false, supportsObjectFragments = false) { op, selections -> op.resolve06(selections) },
        Family("07", false) { op, selections -> op.resolve07(selections) },
        Family("08", true) { op, selections -> op.resolve08(selections) },
        Family("21", false, supportsObjectFragments = false) { op, selections -> op.resolve21(selections) },
        Family("22", false) { op, selections -> op.resolve22(selections) },
        Family("23", true) { op, selections -> op.resolve23(selections) },
        Family("Resolution", true) { op, selections -> op.resolveWithTestDispatcher(selections) },
        Family("06 precomputed", false, precomputed = true, supportsObjectFragments = false) { op, selections -> op.resolve06(selections) },
        Family("07 precomputed", false, precomputed = true) { op, selections -> op.resolve07(selections) },
        Family("08 precomputed", true, precomputed = true) { op, selections -> op.resolve08(selections) },
        Family("21 precomputed", false, precomputed = true, supportsObjectFragments = false) { op, selections -> op.resolve21(selections) },
        Family("22 precomputed", false, precomputed = true) { op, selections -> op.resolve22(selections) },
        Family("23 precomputed", true, precomputed = true) { op, selections -> op.resolve23(selections) },
        Family("Resolution precomputed", true, precomputed = true) { op, selections -> op.resolveWithTestDispatcher(selections) },
    )

    @TestFactory
    fun `mutations traverse namespaces depth first and preserve separate alias results`(): List<DynamicTest> =
        families.map { family ->
            DynamicTest.dynamicTest(family.name) {
                val count = AtomicInteger()
                val events = mutableListOf<String>()
                val fixture = TestWorld.fromSDL(
                    """
                    directive @resolver on FIELD_DEFINITION
                    directive @namespaceType on OBJECT
                    type Query { idle: Int }
                    type Mutation { group: Group, update: Payload @resolver }
                    type Group @namespaceType { nested: Nested, update: Payload @resolver }
                    type Nested @namespaceType { update: Payload @resolver }
                    type Payload { value: Int, observed: Int @resolver }
                    """.trimIndent(),
                    fieldResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        buildMap {
                            put(schema.requireObjectField("Query", "idle"), fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 0 })
                            for (type in listOf("Mutation", "Group", "Nested")) {
                                put(
                                    schema.requireObjectField(type, "update"),
                                    fieldResolverOf(schema.emptyFragmentOf(type), schema.emptyFragmentOf("Query")) { input, queryValue, _ ->
                                        assertEquals(type, input.type.name)
                                        assertTrue(input.getSelections().none())
                                        assertEquals("Query", queryValue.type.name)
                                        assertTrue(queryValue.getSelections().none())
                                        val value = count.incrementAndGet()
                                        events += "start:$value"
                                        yield()
                                        events += "end:$value"
                                        engineObjectDataOf(schema.requireType("Payload") as ViaductSchema.Object, mapOf("value" to value))
                                    }
                                )
                            }
                            put(
                                schema.requireObjectField("Payload", "observed"),
                                fieldResolverOf(schema.emptyFragmentOf("Payload")) { _, _ ->
                                    yield()
                                    count.get()
                                }
                            )
                        }
                    },
                )
                val selections = fixture.schemas.operationSelectionsFrom(
                    """
                    mutation {
                        group { a: update { value observed } nested { update { value observed } } b: update { value observed } }
                        first: update { value observed }
                        second: update { value observed }
                        first: update { observed }
                    }
                    """.trimIndent(),
                )
                val result = family.resolve(SharedOperationContext.create(fixture.newAssumptions(usePrecomputedConstructionDemand = family.precomputed)), selections)
                assertInstanceOf(MutationObjectEngineResult::class.java, result)
                assertEquals((1..5).flatMap { listOf("start:$it", "end:$it") }, events)
                assertEquals(
                    mapOf("group" to mapOf("a" to payload(1), "nested" to mapOf("update" to payload(2)), "b" to payload(3)), "first" to payload(4), "second" to payload(5)),
                    result.completedData()
                )
            }
        }

    @TestFactory
    fun `mutation payload Query fragments observe each mutation before the next effect`(): List<DynamicTest> =
        families.map { family ->
            DynamicTest.dynamicTest(family.name) {
                val count = AtomicInteger()
                val observed = mutableListOf<Int>()
                val fixture = TestWorld.fromDSL(
                    """
                    extend type Query { current: Int @resolver(script: "var value = count.get(); observed.add(value); value") }
                    type Mutation { update: Payload @resolver(script: "count.incrementAndGet(); {:}") }
                    type Payload { observed: Int @resolver(qf: "current", script: "queryValue.current") }
                    """.trimIndent(),
                    scriptBindings = mapOf("count" to count, "observed" to observed),
                )
                val result = family.resolve(
                    SharedOperationContext.create(fixture.newAssumptions(usePrecomputedConstructionDemand = family.precomputed)),
                    fixture.schemas.operationSelectionsFrom("mutation { first: update { observed } second: update { observed } }"),
                )
                assertEquals(listOf(1, 2), observed)
                assertEquals(mapOf("first" to mapOf("observed" to 1), "second" to mapOf("observed" to 2)), result.completedData())
            }
        }

    @TestFactory
    fun `mutation payload object dependencies finish before the next effect and respect supplied fields`(): List<DynamicTest> =
        families.filter { it.supportsObjectFragments }.map { family ->
            DynamicTest.dynamicTest(family.name) {
                val count = AtomicInteger()
                val events = mutableListOf<String>()
                val providerReads = mutableListOf<Int>()
                val queryReads = mutableListOf<Int>()
                val fixture = TestWorld.fromSDL(
                    """
                    type Query { current: Int }
                    type Mutation { update(supplied: Boolean!): Payload }
                    type Payload { result: Int middle: Int seed(value: Int): Int snapshot: Int }
                    """.trimIndent(),
                    fieldResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        val payloadType = schema.requireType("Payload") as ViaductSchema.Object
                        mapOf(
                            schema.requireObjectField("Mutation", "update") to fieldResolverOf(schema.emptyFragmentOf("Mutation")) { _, arguments ->
                                val value = count.incrementAndGet()
                                events += "mutation:$value"
                                yield()
                                engineObjectDataOf(
                                    payloadType,
                                    buildMap {
                                        put("snapshot", value)
                                        if (arguments.fieldValues.getValue("supplied") as Boolean) put("middle", 100 + value)
                                    },
                                )
                            },
                            schema.requireObjectField("Payload", "result") to fieldResolverOf(
                                schemas.fragmentFrom("fragment ResultInput on Payload { middle }"),
                            ) { input, _ ->
                                events += "payload:${count.get()}"
                                input.get("middle")
                            },
                            schema.requireObjectField("Payload", "middle") to fieldResolverOf(
                                schemas.fragmentFrom("fragment MiddleInput on Payload { seed(value: ${'$'}provided) }"),
                                schemas.fragmentFrom("fragment MiddleQuery on Query { current }"),
                            ) { input, queryValue, _ ->
                                (input.get("seed") as Int) + (queryValue.get("current") as Int)
                            }.withVariablesProvider(setOf("provided")) {
                                val value = count.get()
                                yield()
                                providerReads += value
                                mapOf("provided" to 10 * value)
                            },
                            schema.requireObjectField("Payload", "seed") to fieldResolverOf(
                                schemas.fragmentFrom("fragment SeedInput on Payload { snapshot }"),
                            ) { input, arguments ->
                                (input.get("snapshot") as Int) + (arguments.fieldValues.getValue("value") as Int)
                            },
                            schema.requireObjectField("Query", "current") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                yield()
                                count.get().also { queryReads += it }
                            },
                        )
                    },
                )
                val result = family.resolve(
                    SharedOperationContext.create(fixture.newAssumptions(usePrecomputedConstructionDemand = family.precomputed)),
                    fixture.schemas.operationSelectionsFrom(
                        "mutation { first: update(supplied: false) { result } second: update(supplied: true) { result } third: update(supplied: false) { result } }",
                    ),
                )

                assertEquals((1..3).flatMap { listOf("mutation:$it", "payload:$it") }, events)
                assertEquals(listOf(1, 3), providerReads)
                assertEquals(listOf(1, 3), queryReads)
                assertEquals(
                    mapOf(
                        "first" to mapOf("snapshot" to 1, "seed" to 11, "middle" to 12, "result" to 12),
                        "second" to mapOf("snapshot" to 2, "middle" to 102, "result" to 102),
                        "third" to mapOf("snapshot" to 3, "seed" to 33, "middle" to 36, "result" to 36),
                    ),
                    result.completedData(),
                )
            }
        }

    @TestFactory
    fun `nonnull mutation failures preserve all namespace effects across every family`(): List<DynamicTest> =
        families.flatMap { family ->
            listOf(false, true).map { nonNullNamespace ->
                DynamicTest.dynamicTest("${family.name}: nonnull namespace=$nonNullNamespace") {
                    val calls = AtomicInteger()
                    val namespaceType = if (nonNullNamespace) "Group!" else "Group"
                    val fixture = TestWorld.fromDSL(
                        """
                        directive @namespaceType on OBJECT
                        extend type Query { idle: Int }
                        type Mutation {
                          group: $namespaceType
                          update: Int @resolver(script: "calls.incrementAndGet()")
                        }
                        type Group @namespaceType {
                          update: Int! @resolver(script: "calls.incrementAndGet() == 1 ? failure : calls.get()")
                        }
                        """.trimIndent(),
                        scriptBindings = mapOf(
                            "calls" to calls,
                            "failure" to EngineErrorData.of(IllegalStateException("mutation failed")),
                        ),
                    )
                    val result = family.resolve(
                        SharedOperationContext.create(fixture.newAssumptions(usePrecomputedConstructionDemand = family.precomputed)),
                        fixture.schemas.operationSelectionsFrom("mutation { group { first: update second: update } last: update }"),
                    )
                    assertEquals(3, calls.get())
                    val groupKey = result.keys.single { (it as ObjectEngineResult.MutationKey).responseKey == "group" }
                    val group = result.getCell(groupKey).value.get() as ObjectEngineResult
                    val secondKey = group.keys.single { (it as ObjectEngineResult.MutationKey).responseKey == "second" }
                    assertTrue(runBlocking { group.getCell(secondKey).fetchActivated() })
                    assertEquals(2, group.getCell(secondKey).value.get())
                    val lastKey = result.keys.single { (it as ObjectEngineResult.MutationKey).responseKey == "last" }
                    assertTrue(runBlocking { result.getCell(lastKey).fetchActivated() })
                    assertEquals(3, result.getCell(lastKey).value.get())
                }
            }
        }

    @TestFactory
    fun `mutation field checks gate aliased effects and isolate failures`(): List<DynamicTest> =
        families.filter { it.name.substringBefore(" ") in setOf("21", "22", "23", "Resolution") }.map { family ->
            DynamicTest.dynamicTest(family.name) {
                val effects = mutableListOf<Int>()
                val checks = mutableListOf<Int>()
                val recorder = CheckerApplicationRecorder()
                val failure = IllegalStateException("checker failed")
                val fixture = TestWorld.fromDSL(
                    """
                    extend type Query { idle: Int }
                    type Mutation {
                      update(value: Int): Int @resolver(script: "effects.add(value); value")
                    }
                    """.trimIndent(),
                    scriptBindings = mapOf("effects" to effects),
                    fieldCheckers = { schemas ->
                        val schema = schemas.loweredSchema
                        val field = schema.requireObjectField("Mutation", "update")
                        val empty = ResolverFragmentTemplates(materializeSelectionForestOf(), materializeSelectionForestOf())
                        mapOf(
                            field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef(), mapOf("left" to empty, "right" to empty)) { args, inputs, _ ->
                                assertEquals(setOf("left", "right"), inputs.keys)
                                inputs.values.forEach {
                                    assertEquals("Mutation", it.objectValue.type.name)
                                    assertEquals("Query", it.queryValue.type.name)
                                    assertTrue(it.objectValue.getSelections().none())
                                    assertTrue(it.queryValue.getSelections().none())
                                }
                                val value = args.fieldValues.getValue("value") as Int
                                checks += value
                                when (value) {
                                    -1 -> MutationDenial
                                    0 -> throw failure
                                    else -> CheckerResult.Success
                                }
                            }
                        )
                    },
                )
                val result = family.resolve(
                    SharedOperationContext.create(fixture.newAssumptions(usePrecomputedConstructionDemand = family.precomputed), resolutionObserver = recorder),
                    fixture.schemas.operationSelectionsFrom("mutation { a: update(value: 1) denied: update(value: -1) failed: update(value: 0) b: update(value: 2) }"),
                )
                val field = fixture.schema.requireObjectField("Mutation", "update")
                val expected = listOf("a" to 1, "denied" to -1, "failed" to 0, "b" to 2).map { (alias, value) ->
                    val key = ObjectEngineResult.MutationKey.of(ObjectEngineResult.GroundKey.of(field, mapOf("value" to value)), alias)
                    CheckerInvocationObservation(CheckerKind.FIELD, result, listOf(key), key.arguments as Arguments.Resolved, ResolverTarget.FieldCheckerTarget(field))
                }
                assertEquals(expected, recorder.checkerApplications())
                assertEquals(listOf(1, -1, 0, 2), checks)
                assertEquals(listOf(1, 2), effects)
                assertEquals(mapOf("a" to 1, "denied" to null, "failed" to null, "b" to 2), result.completedData())
                val cells = result.keys.associate { (it as ObjectEngineResult.MutationKey).responseKey to result.getCell(it) }
                assertSame(MutationDenial, cells.getValue("denied").fieldCheckerResult.get())
                assertSame(failure, assertThrows<IllegalStateException> { cells.getValue("failed").fieldCheckerResult.get() })
            }
        }

    @TestFactory
    fun `nested namespace checks gate descendants and retain sibling effects`(): List<DynamicTest> =
        families.filter { it.name.substringBefore(" ") in setOf("21", "22", "23", "Resolution") }.map { family ->
            DynamicTest.dynamicTest(family.name) {
                val events = mutableListOf<String>()
                val preparedPaths = mutableListOf<List<String>>()
                var groupTypeCalls = 0
                val fixture = TestWorld.fromDSL(
                    """
                    directive @namespaceType on OBJECT
                    extend type Query { idle: Int }
                    type Mutation {
                      group: Group
                      hidden: Group
                      update: Int @resolver(script: "events.add('effect:Mutation'); 7")
                    }
                    type Group @namespaceType {
                      nested: Nested
                      update: Int @resolver(script: "events.add('effect:Group'); 7")
                      blocked: Int @resolver(script: "throw 'Denied field ran'")
                    }
                    type Nested @namespaceType {
                      update: Int @resolver(script: "throw 'Denied field ran'")
                    }
                    """.trimIndent(),
                    scriptBindings = mapOf("events" to events),
                    fieldCheckers = { schemas ->
                        val schema = schemas.loweredSchema
                        listOf("Mutation" to "group", "Mutation" to "hidden", "Group" to "blocked", "Group" to "nested", "Nested" to "update").associate { (owner, name) ->
                            val field = schema.requireObjectField(owner, name)
                            field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef()) { _, _, _ ->
                                events += "field:$owner.$name"
                                if (name == "group" || name == "nested") CheckerResult.Success else MutationDenial
                            }
                        }
                    },
                    typeCheckers = { schemas ->
                        val schema = schemas.loweredSchema
                        listOf("Group", "Nested").associate { name ->
                            val type = schema.requireType(name) as ViaductSchema.Object
                            type to TypeCheckerResolver.of(type, schema.requireQueryTypeDef()) { _, _ ->
                                events += "type:$name"
                                if (name == "Group" && ++groupTypeCalls == 1) CheckerResult.Success else MutationDenial
                            }
                        }
                    },
                )
                val observer = object : ResolutionObserver {
                    override val onTaskPrepared: (ResolutionTask) -> Unit = { task ->
                        if (task is MutationOrchestrationTask<*>) {
                            preparedPaths += task.occurrence.path.map { (it as ObjectEngineResult.MutationKey).responseKey }
                        }
                    }
                }
                val result = family.resolve(
                    SharedOperationContext.create(fixture.newAssumptions(usePrecomputedConstructionDemand = family.precomputed), resolutionObserver = observer),
                    fixture.schemas.operationSelectionsFrom("mutation { first: group { blocked nested { update } update } hidden { update } denied: group { nested { update } update } update }"),
                )
                assertEquals(
                    listOf(
                        "field:Mutation.group",
                        "type:Group",
                        "field:Group.blocked",
                        "field:Group.nested",
                        "type:Nested",
                        "effect:Group",
                        "field:Mutation.hidden",
                        "field:Mutation.group",
                        "type:Group",
                        "effect:Mutation",
                    ),
                    events,
                )
                assertEquals(listOf(emptyList(), listOf("first"), listOf("first", "nested"), listOf("denied")), preparedPaths)
                val groups = result.keys.filter { it.field.name == "group" }.associate { key ->
                    (key as ObjectEngineResult.MutationKey).responseKey to assertInstanceOf(ObjectEngineResult::class.java, result.getCell(key).value.get())
                }
                val first = groups.getValue("first")
                assertSame(CheckerResult.Success, first.typeCheckerResult.get())
                assertSame(MutationDenial, groups.getValue("denied").typeCheckerResult.get())
                val nestedKey = first.keys.single { it.field.name == "nested" }
                assertSame(CheckerResult.Success, first.getCell(nestedKey).fieldCheckerResult.get())
                val nested = assertInstanceOf(ObjectEngineResult::class.java, first.getCell(nestedKey).value.get())
                assertSame(MutationDenial, nested.typeCheckerResult.get())
                nested.keys.forEach { key ->
                    val cell = nested.getCell(key)
                    assertTrue(cell.isCompleted)
                    assertEquals(null, cell.fieldCheckerResult.get())
                    assertEquals(null, cell.value.get())
                }
                assertEquals(
                    mapOf(
                        "first" to mapOf("blocked" to null, "nested" to mapOf("update" to null), "update" to 7),
                        "hidden" to null,
                        "denied" to mapOf("nested" to null, "update" to null),
                        "update" to 7,
                    ),
                    result.completedData(),
                )
            }
        }

    @Test
    fun `mutation checkers query fresh raw state before namespace effects`() {
        val current = AtomicInteger()
        val events = mutableListOf<String>()
        val fixture = TestWorld.fromDSL(
            """
            directive @namespaceType on OBJECT
            extend type Query { current: Int @resolver(script: "current.get()") }
            type Mutation { group: Group }
            type Group @namespaceType {
              update: Int @resolver(script: "var value = current.incrementAndGet(); events.add('effect:' + value); value")
            }
            """.trimIndent(),
            scriptBindings = mapOf("current" to current, "events" to events),
            fieldCheckers = { schemas ->
                val schema = schemas.loweredSchema
                listOf("Query" to "current", "Group" to "update").associate { (owner, name) ->
                    val field = schema.requireObjectField(owner, name)
                    field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef()) { _, _, ctx ->
                        check(owner == "Group") { "Checker queries must read raw values" }
                        val value = ctx.resolveSelectionSet(schemas.fragmentFrom("fragment Current on Query { current }").materializeSelections).get("current")
                        events += "field:$value"
                        CheckerResult.Success
                    }
                }
            },
            typeCheckers = { schemas ->
                val schema = schemas.loweredSchema
                val type = schema.requireType("Group") as ViaductSchema.Object
                mapOf(
                    type to TypeCheckerResolver.of(type, schema.requireQueryTypeDef()) { _, ctx ->
                        val value = ctx.resolveSelectionSet(schemas.fragmentFrom("fragment Current on Query { current }").materializeSelections).get("current")
                        events += "type:$value"
                        CheckerResult.Success
                    }
                )
            },
        )
        val result = SharedOperationContext.create(fixture.assumptions).resolveWithTestDispatcher(
            fixture.schemas.operationSelectionsFrom("mutation { first: group { update } second: group { update } }"),
        )
        assertEquals(listOf("type:0", "field:0", "effect:1", "type:1", "field:1", "effect:2"), events)
        assertEquals(mapOf("first" to mapOf("update" to 1), "second" to mapOf("update" to 2)), result.completedData())
    }

    @TestFactory
    fun `namespace applicability and checker exceptions remain local to each alias`(): List<DynamicTest> =
        families.filter { it.name.substringBefore(" ") in setOf("21", "22", "23", "Resolution") }.map { family ->
            DynamicTest.dynamicTest(family.name) {
                val effects = mutableListOf<String>()
                val calls = mutableListOf<String>()
                val failure = CancellationException("checker-local cancellation")
                val fixture = TestWorld.fromDSL(
                    """
                    directive @namespaceType on OBJECT
                    extend type Query { idle: Int }
                    type Mutation {
                      group: Group
                      later: Int @resolver(script: "effects.add('later'); 7")
                    }
                    type Group @namespaceType {
                      update: Int @resolver(script: "effects.add('update'); 7")
                    }
                    """.trimIndent(),
                    scriptBindings = mapOf("effects" to effects),
                    typeCheckers = { schemas ->
                        val schema = schemas.loweredSchema
                        val type = schema.requireType("Group") as ViaductSchema.Object
                        mapOf(
                            type to TypeCheckerResolver.of(
                                type,
                                schema.requireQueryTypeDef(),
                                isApplicable = { _, path ->
                                    val alias = (path.single() as ObjectEngineResult.MutationKey).responseKey
                                    calls += "applicable:$alias"
                                    alias != "skipped"
                                },
                            ) { _, _ ->
                                calls += "checker"
                                throw failure
                            }
                        )
                    },
                )
                val result = family.resolve(
                    SharedOperationContext.create(fixture.newAssumptions(usePrecomputedConstructionDemand = family.precomputed)),
                    fixture.schemas.operationSelectionsFrom("mutation { skipped: group { update } failed: group { update } later }"),
                )
                assertEquals(listOf("applicable:skipped", "applicable:failed", "checker"), calls)
                assertEquals(listOf("update", "later"), effects)
                val namespaces = result.keys.filter { it.field.name == "group" }.associate { key ->
                    (key as ObjectEngineResult.MutationKey).responseKey to assertInstanceOf(ObjectEngineResult::class.java, result.getCell(key).value.get())
                }
                assertEquals(null, namespaces.getValue("skipped").typeCheckerResult.get())
                assertSame(failure, assertThrows<CancellationException> { namespaces.getValue("failed").typeCheckerResult.get() })
                assertTrue(namespaces.getValue("failed").isCompleted)
                assertTrue(result.isCompleted)
            }
        }

    @Test
    fun `cancelling mutation checks terminates prepared namespace promises`() =
        runBlocking {
            for (checkedType in listOf("Mutation", "Group", "Nested")) {
                val typeCheck = checkedType != "Mutation"
                val entered = CompletableDeferred<Unit>()
                val prepared = ConcurrentLinkedQueue<ObjectEngineResult>()
                val fixture = TestWorld.fromSDL(
                    """
                directive @namespaceType on OBJECT
                type Query { idle: Int }
                type Mutation { group: Group, later: Int }
                type Group @namespaceType { nested: Nested, update: Int }
                type Nested @namespaceType { update: Int }
                    """.trimIndent(),
                    fieldResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        listOf("Group" to "update", "Nested" to "update", "Mutation" to "later").associate { (owner, name) ->
                            schema.requireObjectField(owner, name) to fieldResolverOf(schema.emptyFragmentOf(owner)) { _, _ -> error("Cancelled mutation ran") }
                        }
                    },
                    fieldCheckers = { schemas ->
                        val schema = schemas.loweredSchema
                        val field = schema.requireObjectField("Mutation", "group")
                        if (typeCheck) {
                            emptyMap()
                        } else {
                            mapOf(
                                field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef()) { _, _, _ ->
                                    entered.complete(Unit)
                                    awaitCancellation()
                                }
                            )
                        }
                    },
                    typeCheckers = { schemas ->
                        val schema = schemas.loweredSchema
                        val type = schema.requireType(checkedType) as ViaductSchema.Object
                        if (!typeCheck) {
                            emptyMap()
                        } else {
                            mapOf(
                                type to TypeCheckerResolver.of(type, schema.requireQueryTypeDef()) { _, _ ->
                                    entered.complete(Unit)
                                    awaitCancellation()
                                }
                            )
                        }
                    },
                )
                val observer = object : ResolutionObserver {
                    override val onTaskPrepared: (ResolutionTask) -> Unit = { task ->
                        if (task is MutationOrchestrationTask<*>) prepared += assertInstanceOf(MutationObjectEngineResult::class.java, task.occurrence.target)
                    }
                }
                val operation = SharedOperationContext.create(fixture.assumptions, resolutionObserver = observer)
                val job = Job()
                operation.startResolve(
                    fixture.schemas.operationSelectionsFrom("mutation { group { nested { update } update } later }"),
                    CoroutineScope(job + resolverDispatcher),
                )
                try {
                    withTimeout(2_000) { entered.await() }
                } finally {
                    job.cancel()
                    withTimeout(2_000) { job.join() }
                }
                val expectedNamespaces = when (checkedType) {
                    "Mutation" -> listOf("Mutation")
                    "Group" -> listOf("Mutation", "Group")
                    else -> listOf("Mutation", "Group", "Nested")
                }
                assertEquals(expectedNamespaces, prepared.map { it.type.name })
                prepared.forEach { namespace ->
                    assertTrue(namespace.typeCheckerResult.isCompleted)
                    if (typeCheck && namespace.type.name == checkedType) {
                        assertThrows<CancellationException> { namespace.typeCheckerResult.get() }
                    } else {
                        assertEquals(null, namespace.typeCheckerResult.get())
                    }
                    namespace.keys.forEach { key ->
                        val cell = namespace.getCell(key)
                        assertTrue(cell.isCompleted)
                        assertThrows<CancellationException> { cell.value.get() }
                    }
                }
            }
        }

    @Test
    fun `cancellation after namespace preparation prevents type checker entry`() =
        runBlocking {
            val calls = AtomicInteger()
            val prepared = CompletableDeferred<MutationObjectEngineResult>()
            val job = Job()
            val fixture = TestWorld.fromSDL(
                """
            directive @namespaceType on OBJECT
            type Query { idle: Int }
            type Mutation { group: Group }
            type Group @namespaceType { update: Int }
                """.trimIndent(),
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    mapOf(schema.requireObjectField("Group", "update") to fieldResolverOf(schema.emptyFragmentOf("Group")) { _, _ -> error("Cancelled mutation ran") })
                },
                typeCheckers = { schemas ->
                    val schema = schemas.loweredSchema
                    val type = schema.requireType("Group") as ViaductSchema.Object
                    mapOf(
                        type to TypeCheckerResolver.of(type, schema.requireQueryTypeDef()) { _, _ ->
                            calls.incrementAndGet()
                            CheckerResult.Success
                        }
                    )
                },
            )
            val observer = object : ResolutionObserver {
                override val onTaskPrepared: (ResolutionTask) -> Unit = { task ->
                    if (task is MutationOrchestrationTask<*> && task.occurrence.target.type.name == "Group") {
                        prepared.complete(assertInstanceOf(MutationObjectEngineResult::class.java, task.occurrence.target))
                        job.cancel()
                    }
                }
            }
            SharedOperationContext.create(fixture.assumptions, resolutionObserver = observer).startResolve(
                fixture.schemas.operationSelectionsFrom("mutation { group { update } }"),
                CoroutineScope(job + resolverDispatcher),
            )
            withTimeout(2_000) { job.join() }
            val namespace = withTimeout(2_000) { prepared.await() }
            assertEquals(0, calls.get())
            assertThrows<CancellationException> { namespace.typeCheckerResult.get() }
            assertTrue(namespace.isCompleted)
        }

    private object MutationDenial : CheckerResult.Error {
        override val error = SecurityException("denied")

        override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = false

        override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
    }

    private fun payload(value: Int) = mapOf("value" to value, "observed" to value)

    private fun ObjectEngineResult.completedData(): Map<String, Any?> =
        keys.associate { key ->
            assertTrue(isCompleted, "Incomplete mutation or payload object: ${type.name}")
            val name = (key as? ObjectEngineResult.MutationKey)?.responseKey ?: key.field.name
            val value = getCell(key).value.get()
            name to if (value is ObjectEngineResult) value.completedData() else value
        }
}
