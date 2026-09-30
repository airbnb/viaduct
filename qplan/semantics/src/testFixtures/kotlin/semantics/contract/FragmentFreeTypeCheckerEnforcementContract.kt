@file:Suppress("ForbiddenImport")

package semantics.contract

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.EngineErrorData
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.RootFieldReferenceData
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
import semantics.shared.CheckerKind
import semantics.shared.CycleCheckState
import semantics.shared.CycleSlot
import semantics.shared.CycleSlotKind
import semantics.shared.CycleTask
import semantics.shared.CycleTaskKind
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

/** Fragment-free type-checker enforcement at Resolver22/23 ordinary resolver-input boundaries. */
interface FragmentFreeTypeCheckerEnforcementContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `singular field and type results enforce independently and combine in production order`() {
        data class Case(
            val dependency: String,
            val consumer: String,
            val type: String,
        )

        val cases =
            listOf(
                Case("fieldOnly", "consumeFieldOnly", "FieldOnly"),
                Case("typeOnly", "consumeTypeOnly", "TypeOnly"),
                Case("bothAllowed", "consumeBothAllowed", "BothAllowed"),
                Case("fieldDenied", "consumeFieldDenied", "FieldDenied"),
                Case("typeDenied", "consumeTypeDenied", "TypeDenied"),
                Case("bothDenied", "consumeBothDenied", "BothDenied"),
                Case("fieldDeniedTypeAllowed", "consumeFieldDeniedTypeAllowed", "FieldDeniedTypeAllowed"),
                Case("fieldAllowedTypeDenied", "consumeFieldAllowedTypeDenied", "FieldAllowedTypeDenied"),
            )
        val fieldDenial = TypeEnforcementError("field denied")
        val typeDenial = TypeEnforcementError("type denied")
        val combinedDenial = TypeEnforcementError("combined denial")
        val combiningTypeDenial = CombiningTypeEnforcementError(fieldDenial, combinedDenial)
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      fieldOnly: FieldOnly!
                      consumeFieldOnly: Int!
                      typeOnly: TypeOnly!
                      consumeTypeOnly: Int!
                      bothAllowed: BothAllowed!
                      consumeBothAllowed: Int!
                      fieldDenied: FieldDenied!
                      consumeFieldDenied: Int!
                      typeDenied: TypeDenied!
                      consumeTypeDenied: Int!
                      bothDenied: BothDenied!
                      consumeBothDenied: Int!
                      fieldDeniedTypeAllowed: FieldDeniedTypeAllowed!
                      consumeFieldDeniedTypeAllowed: Int!
                      fieldAllowedTypeDenied: FieldAllowedTypeDenied!
                      consumeFieldAllowedTypeDenied: Int!
                    }

                    type FieldOnly { value: Int! }
                    type TypeOnly { value: Int! }
                    type BothAllowed { value: Int! }
                    type FieldDenied { value: Int! }
                    type TypeDenied { value: Int! }
                    type BothDenied { value: Int! }
                    type FieldDeniedTypeAllowed { value: Int! }
                    type FieldAllowedTypeDenied { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    buildMap {
                        cases.forEachIndexed { index, case ->
                            val dependency = schema.loweredSchema.requireObjectField("Query", case.dependency)
                            val consumer = schema.loweredSchema.requireObjectField("Query", case.consumer)
                            put(
                                dependency,
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                    schema.loweredSchema.objectOf(case.type) { "value" setTo index + 1 }
                                },
                            )
                            put(
                                consumer,
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment Input on Query { ${case.dependency} { value } }",
                                    ),
                                ) { input, _ ->
                                    when (val dependencyValue = input.outputValue(case.dependency)) {
                                        is EngineErrorData -> dependencyValue
                                        is EngineObjectData.Sync -> dependencyValue.outputValue("value")
                                        else -> error("Unexpected dependency value: $dependencyValue")
                                    }
                                },
                            )
                        }
                    }
                },
                fieldCheckers = { schema ->
                    listOf(
                        "fieldOnly" to CheckerResult.Success,
                        "bothAllowed" to CheckerResult.Success,
                        "fieldDenied" to fieldDenial,
                        "bothDenied" to fieldDenial,
                        "fieldDeniedTypeAllowed" to fieldDenial,
                        "fieldAllowedTypeDenied" to CheckerResult.Success,
                    ).associate { (name, result) ->
                        val field = schema.loweredSchema.requireObjectField("Query", name)
                        field to
                            FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                result
                            }
                    }
                },
                typeCheckers = { schema ->
                    listOf(
                        "TypeOnly" to CheckerResult.Success,
                        "BothAllowed" to CheckerResult.Success,
                        "TypeDenied" to typeDenial,
                        "BothDenied" to combiningTypeDenial,
                        "FieldDeniedTypeAllowed" to CheckerResult.Success,
                        "FieldAllowedTypeDenied" to typeDenial,
                    ).associate { (name, result) ->
                        val type = schema.loweredSchema.requireType(name) as ViaductSchema.Object
                        type to
                            TypeCheckerResolver.of(type, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                                result
                            }
                    }
                },
            )
        val world = worldFixture.assumptions

        val result =
            resolveT2(
                worldFixture,
                cases.joinToString(prefix = "{ ", postfix = " }") { it.consumer },
            )

        assertEquals(1, result.t2Value(world, "consumeFieldOnly"))
        assertEquals(2, result.t2Value(world, "consumeTypeOnly"))
        assertEquals(3, result.t2Value(world, "consumeBothAllowed"))
        assertSame(fieldDenial.error, result.t2Error(world, "consumeFieldDenied").errorData.cause)
        assertSame(typeDenial.error, result.t2Error(world, "consumeTypeDenied").errorData.cause)
        assertSame(combinedDenial.error, result.t2Error(world, "consumeBothDenied").errorData.cause)
        assertSame(fieldDenial.error, result.t2Error(world, "consumeFieldDeniedTypeAllowed").errorData.cause)
        assertSame(typeDenial.error, result.t2Error(world, "consumeFieldAllowedTypeDenied").errorData.cause)
    }

    @Test
    fun `one type result is shared by directive-sensitive repeated consumers`() {
        val checkerCalls = AtomicInteger()
        val denial = DirectiveAwareTypeEnforcementError()
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    directive @bypassPolicyCheck on FIELD
                    type Query { item: Item!, bypassed: Int!, blocked: Int! }
                    type Item { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val item = schema.loweredSchema.requireObjectField("Query", "item")
                    val bypassed = schema.loweredSchema.requireObjectField("Query", "bypassed")
                    val blocked = schema.loweredSchema.requireObjectField("Query", "blocked")
                    mapOf(
                        item to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Item") { "value" setTo 7 }
                            },
                        bypassed to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Input on Query { item @bypassPolicyCheck { value } }",
                                ),
                            ) { input, _ ->
                                assertIs<EngineObjectData.Sync>(input.outputValue("item")).outputValue("value")
                            },
                        blocked to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { item { value } }"),
                            ) { input, _ -> input.outputValue("item") },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                            checkerCalls.incrementAndGet()
                            denial
                        },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result = resolveT2(worldFixture, "{ bypassed blocked }")

        assertEquals(7, result.t2Value(world, "bypassed"))
        assertSame(denial.error, result.t2Error(world, "blocked").errorData.cause)
        assertEquals(1, checkerCalls.get())
    }

    @Test
    fun `nested list elements enforce their occurrence-owned type results at runtime paths`() {
        val checkerCalls = AtomicInteger()
        val materializedInput = AtomicReference<EngineObjectData.Sync>()
        val recorder = CheckerApplicationRecorder()
        val denial = TypeEnforcementError("list element denied")
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query { matrix: [[Item!]!]!, consumer: Int! }
                    type Item { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val matrix = schema.loweredSchema.requireObjectField("Query", "matrix")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        matrix to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                listOf(
                                    listOf(
                                        schema.loweredSchema.objectOf("Item") { "value" setTo 1 },
                                        schema.loweredSchema.objectOf("Item") { "value" setTo 2 },
                                    ),
                                    listOf(schema.loweredSchema.objectOf("Item") { "value" setTo 3 }),
                                )
                            },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { matrix { value } }"),
                            ) { input, _ ->
                                materializedInput.set(input)
                                1
                            },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                            checkerCalls.incrementAndGet()
                            denial
                        },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world, checkerObserver = recorder),
                worldFixture.schemas.operationSelectionsFrom("{ consumer }"),
            )

        assertEquals(1, result.t2Value(world, "consumer"))
        val rows = assertIs<List<*>>(materializedInput.get().outputValue("matrix"))
        val elementErrors = rows.flatMap { row -> assertIs<List<*>>(row) }.map { assertIs<EngineErrorData>(it) }
        assertEquals(3, elementErrors.size)
        assertTrue(elementErrors.all { it.cause === denial.error })
        assertEquals(3, checkerCalls.get())
        val applications = recorder.checkerApplications().filter { it.checkerKind == CheckerKind.TYPE }
        assertEquals(3, applications.size)
        assertEquals(3, applications.map { it.occurrencePath }.distinct().size)
        assertTrue(
            applications.all { application ->
                application.occurrencePath.count { it is ListEngineResult.Index } == 2
            },
        )
    }

    @Test
    fun `root-field-reference list elements enforce type checks from their independent occurrences`() {
        val checkerCalls = AtomicInteger()
        val materializedInput = AtomicReference<EngineObjectData.Sync>()
        val denial = TypeEnforcementError("referenced item denied")
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query { container: Container!, lookup(id: Int!): Item!, consumer: Int! }
                    type Container { items: [Item!]! }
                    type Item { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.loweredSchema.requireObjectField("Query", "container")
                    val lookup = schema.loweredSchema.requireObjectField("Query", "lookup")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        container to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Container") {
                                    "items" setTo
                                        listOf(
                                            RootFieldReferenceData.of(listOf(lookup), mapOf("id" to 1)),
                                            RootFieldReferenceData.of(listOf(lookup), mapOf("id" to 2)),
                                        )
                                }
                            },
                        lookup to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, arguments ->
                                schema.loweredSchema.objectOf("Item") {
                                    "value" setTo arguments.fieldValues.getValue("id")
                                }
                            },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Input on Query { container { items { value } } }",
                                ),
                            ) { input, _ ->
                                materializedInput.set(input)
                                1
                            },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                            checkerCalls.incrementAndGet()
                            denial
                        },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result = resolveT2(worldFixture, "{ consumer }")

        assertEquals(1, result.t2Value(world, "consumer"))
        val container = assertIs<EngineObjectData.Sync>(materializedInput.get().outputValue("container"))
        val items = assertIs<List<*>>(container.outputValue("items"))
        assertEquals(2, items.size)
        assertTrue(items.all { assertIs<EngineErrorData>(it).cause === denial.error })
        assertEquals(2, checkerCalls.get())
    }

    @Test
    fun `raw checker input bypasses combined field and type denial enforced by an ordinary consumer`() {
        val rawCheckerCalls = AtomicInteger()
        val typeCheckerCalls = AtomicInteger()
        val fieldDenial = TypeEnforcementError("field denied")
        val combinedDenial = TypeEnforcementError("combined denial")
        val typeDenial = CombiningTypeEnforcementError(fieldDenial, combinedDenial)
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query { item: Item!, trigger: Int!, consumer: Int! }
                    type Item { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val item = schema.loweredSchema.requireObjectField("Query", "item")
                    val trigger = schema.loweredSchema.requireObjectField("Query", "trigger")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        item to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Item") { "value" setTo 7 }
                            },
                        trigger to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 1 },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { item { value } }"),
                            ) { input, _ -> input.outputValue("item") },
                    )
                },
                fieldCheckers = { schema ->
                    val item = schema.loweredSchema.requireObjectField("Query", "item")
                    val trigger = schema.loweredSchema.requireObjectField("Query", "trigger")
                    val raw = schema.fragmentFrom("fragment Raw on Query { item { value } }").materializeSelections
                    mapOf(
                        trigger to
                            FieldCheckerResolver.of(
                                trigger,
                                schema.loweredSchema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "raw" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate = raw,
                                                queryFragmentTemplate = materializeSelectionForestOf(),
                                            ),
                                    ),
                            ) { _, inputs, _ ->
                                val rawItem = assertIs<EngineObjectData.Sync>(inputs.getValue("raw").objectValue.outputValue("item"))
                                assertEquals(7, rawItem.outputValue("value"))
                                rawCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                        item to
                            FieldCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                fieldDenial
                            },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                            typeCheckerCalls.incrementAndGet()
                            typeDenial
                        },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result = resolveT2(worldFixture, "{ trigger consumer }")

        assertEquals(1, result.t2Value(world, "trigger"))
        assertSame(combinedDenial.error, result.t2Error(world, "consumer").errorData.cause)
        assertEquals(1, rawCheckerCalls.get())
        assertEquals(1, typeCheckerCalls.get())
    }

    @Test
    fun `parent backedge reuses and enforces the ancestor type result`() {
        val checkerCalls = AtomicInteger()
        val denial = TypeEnforcementError("ancestor denied")
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    directive @parent on FIELD_DEFINITION
                    type Query { root: Root! }
                    type Root { value: Int!, child: Child! }
                    type Child { parent: Root! @parent, consumer: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val root = schema.loweredSchema.requireObjectField("Query", "root")
                    val consumer = schema.loweredSchema.requireObjectField("Child", "consumer")
                    mapOf(
                        root to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Root") {
                                    "value" setTo 7
                                    "child" setTo schema.loweredSchema.objectOf("Child")
                                }
                            },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Child { parent { value } }"),
                            ) { input, _ -> input.outputValue("parent") },
                    )
                },
                typeCheckers = { schema ->
                    val root = schema.loweredSchema.requireType("Root") as ViaductSchema.Object
                    mapOf(
                        root to TypeCheckerResolver.of(root, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                            checkerCalls.incrementAndGet()
                            denial
                        },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result = resolveT2(worldFixture, "{ root { child { consumer } } } ")
        val root = assertIs<ObjectEngineResult>(result.t2Value(world, "root"))
        val child = assertIs<ObjectEngineResult>(root.t2Value(world, "child", "Root"))
        val parent = assertIs<ObjectEngineResult>(child.t2Value(world, "parent", "Child"))

        assertSame(root, parent)
        assertSame(denial.error, child.t2Error(world, "consumer", "Child").errorData.cause)
        assertEquals(1, checkerCalls.get())
    }

    @Test
    fun `type denial takes precedence over a selected child failure and checker failure reaches its consumer`() {
        val childFailure = IllegalStateException("child failed")
        val checkerFailure = IllegalStateException("type checker failed")
        val denial = TypeEnforcementError("type denied")
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query { denied: Denied!, failed: Failed!, consumeDenied: Int!, consumeFailed: Int! }
                    type Denied { value: Int! }
                    type Failed { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val denied = schema.loweredSchema.requireObjectField("Query", "denied")
                    val failed = schema.loweredSchema.requireObjectField("Query", "failed")
                    val deniedValue = schema.loweredSchema.requireObjectField("Denied", "value")
                    val consumeDenied = schema.loweredSchema.requireObjectField("Query", "consumeDenied")
                    val consumeFailed = schema.loweredSchema.requireObjectField("Query", "consumeFailed")
                    mapOf(
                        denied to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> schema.loweredSchema.objectOf("Denied") },
                        failed to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Failed") { "value" setTo 1 }
                            },
                        deniedValue to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Denied")) { _, _ -> throw childFailure },
                        consumeDenied to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { denied { value } }"),
                            ) { input, _ -> input.outputValue("denied") },
                        consumeFailed to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { failed { value } }"),
                            ) { input, _ -> input.outputValue("failed") },
                    )
                },
                typeCheckers = { schema ->
                    val denied = schema.loweredSchema.requireType("Denied") as ViaductSchema.Object
                    val failed = schema.loweredSchema.requireType("Failed") as ViaductSchema.Object
                    mapOf(
                        denied to TypeCheckerResolver.of(denied, schema.loweredSchema.requireQueryTypeDef()) { _, _ -> denial },
                        failed to TypeCheckerResolver.of(failed, schema.loweredSchema.requireQueryTypeDef()) { _, _ -> throw checkerFailure },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result = resolveT2(worldFixture, "{ consumeDenied consumeFailed }")

        assertSame(denial.error, result.t2Error(world, "consumeDenied").errorData.cause)
        assertTrue(
            result
                .t2Error(world, "consumeFailed")
                .errorData
                .cause
                ?.t2CauseSequence()
                .orEmpty()
                .any { it === checkerFailure },
        )
    }

    @Test
    fun `type-checker cancellation cancels its waiting consumer without suppressing siblings`() {
        val cancellation = CancellationException("type checker cancelled")
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query { item: Item!, consumer: Int!, healthy: Int! }
                    type Item { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val item = schema.loweredSchema.requireObjectField("Query", "item")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    val healthy = schema.loweredSchema.requireObjectField("Query", "healthy")
                    mapOf(
                        item to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Item") { "value" setTo 7 }
                            },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { item { value } }"),
                            ) { input, _ -> input.outputValue("item") },
                        healthy to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 42 },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _ -> throw cancellation },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result = resolveT2(worldFixture, "{ consumer healthy }")

        assertTrue(
            result
                .t2Error(world, "consumer")
                .errorData
                .cause
                ?.t2CauseSequence()
                .orEmpty()
                .any { it === cancellation },
        )
        assertEquals(42, result.t2Value(world, "healthy"))
    }

    @Test
    fun `request cancellation during type-checker execution terminates the OER-owned result`() =
        runBlocking {
            val checkerEntered = CompletableDeferred<Unit>()
            val worldFixture =
                TestWorld.fromSDL(
                    selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                    schemaSDL =
                        """
                        type Query { item: Item!, consumer: Int! }
                        type Item { value: Int! }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val item = schema.loweredSchema.requireObjectField("Query", "item")
                        val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                        mapOf(
                            item to
                                fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                    schema.loweredSchema.objectOf("Item") { "value" setTo 7 }
                                },
                            consumer to
                                fieldResolverOf(
                                    schema.fragmentFrom("fragment Input on Query { item { value } }"),
                                ) { input, _ -> input.outputValue("item") },
                        )
                    },
                    typeCheckers = { schema ->
                        val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                        mapOf(
                            item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                                checkerEntered.complete(Unit)
                                CompletableDeferred<Nothing>().await()
                            },
                        )
                    },
                )
            val world = worldFixture.assumptions
            val requestJob = Job()
            val requestScope = CoroutineScope(coroutineContext + requestJob)
            val cancellation = CancellationException("request cancelled during type checker")
            try {
                val result =
                    coroutineResolverSubject.startResolution(
                        SharedOperationContext.create(world),
                        requestScope,
                        worldFixture.schemas.operationSelectionsFrom("{ consumer }"),
                        CycleCheckState.create(),
                    )
                withTimeout(5_000) { checkerEntered.await() }
                val item = assertIs<ObjectEngineResult>(result.t2Value(world, "item"))

                requestJob.cancel(cancellation)
                withTimeout(5_000) { requestJob.join() }

                val checkerFailure =
                    assertFailsWith<CancellationException> {
                        item.typeCheckerResult.await()
                    }
                assertEquals(cancellation.message, checkerFailure.message)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `type-checker writer and resolver read use the OER-owned type slot`() {
        data class Writer(val slot: CycleSlot, val task: CycleTask)

        data class Read(val slot: CycleSlot, val task: CycleTask)

        val writers = mutableListOf<Writer>()
        val reads = mutableListOf<Read>()
        val cycleChecker =
            object : CycleCheckState {
                override fun registerWriter(
                    slot: CycleSlot,
                    writer: CycleTask,
                ) {
                    if (slot.kind == CycleSlotKind.TYPE_CHECKER) writers += Writer(slot, writer)
                }

                override fun cycleCheck(
                    reader: CycleTask,
                    slot: CycleSlot,
                ) {
                    if (slot.kind == CycleSlotKind.TYPE_CHECKER) reads += Read(slot, reader)
                }
            }
        val worldFixture =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query { item: Item!, consumer: Int! }
                    type Item { value: Int! }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val item = schema.loweredSchema.requireObjectField("Query", "item")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        item to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                schema.loweredSchema.objectOf("Item") { "value" setTo 7 }
                            },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment Input on Query { item { value } }"),
                            ) { input, _ ->
                                assertIs<EngineObjectData.Sync>(input.outputValue("item")).outputValue("value")
                            },
                    )
                },
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { _, _ -> CheckerResult.Success },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                worldFixture.schemas.operationSelectionsFrom("{ consumer }"),
                cycleChecker,
            )

        assertEquals(7, result.t2Value(world, "consumer"))
        assertEquals(1, writers.size)
        assertEquals(1, reads.size)
        assertEquals(CycleTaskKind.TYPE_CHECKER, writers.single().task.kind)
        assertEquals(CycleTaskKind.FIELD_RESOLVER, reads.single().task.kind)
        assertEquals(writers.single().slot, reads.single().slot)
    }

    private fun resolveT2(
        world: TestWorld,
        query: String,
    ): ObjectEngineResult =
        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world.assumptions),
            world.schemas.operationSelectionsFrom(query),
        )
}

private fun ObjectEngineResult.t2Value(
    world: model.Assumptions,
    fieldName: String,
    typeName: String = "Query",
): Any? =
    getCell(
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField(typeName, fieldName),
            emptyMap(),
        ),
    ).value.get()

private fun ObjectEngineResult.t2Error(
    world: model.Assumptions,
    fieldName: String,
    typeName: String = "Query",
): ErrorEngineResult = assertIs(t2Value(world, fieldName, typeName))

private fun Throwable.t2CauseSequence(): Sequence<Throwable> = generateSequence(this) { it.cause }

private open class TypeEnforcementError(message: String) : CheckerResult.Error {
    override val error: Exception = IllegalStateException(message)

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}

private class CombiningTypeEnforcementError(
    private val expectedFieldError: CheckerResult.Error,
    private val combinedError: CheckerResult.Error,
) : TypeEnforcementError("uncombined type denial") {
    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error {
        assertSame(expectedFieldError, fieldResult)
        return combinedError
    }
}

/** The test checker, rather than qplan, assigns meaning to Airbnb's directive spelling. */
private class DirectiveAwareTypeEnforcementError : CheckerResult.Error {
    override val error: Exception = IllegalStateException("type denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean {
        val directives = checkNotNull(ctx.fieldDirectives)
        return !directives.hasDirective("bypassPolicyCheck") { arguments -> arguments.isEmpty() }
    }

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
