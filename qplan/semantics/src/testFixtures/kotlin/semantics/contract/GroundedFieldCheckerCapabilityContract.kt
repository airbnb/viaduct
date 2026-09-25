package semantics.contract

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import model.EngineResultCell
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.objectOf
import model.operationSelectionsFrom
import model.registry.FieldChecker
import model.registry.ResolverFragmentTemplates
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineObjectData

/** Load-bearing grounded checker semantics composed by Resolver22. */
interface GroundedFieldCheckerCapabilityContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `raw checker demand resumes checked semantics at an active resolver boundary`() {
        val cases =
            listOf(
                ChainCase(cResult = CheckerResult.Success, readC = true, bypassC = false),
                ChainCase(cResult = ChainDenial("read denial"), readC = true, bypassC = false),
                ChainCase(cResult = ChainDenial("ignored denial"), readC = false, bypassC = false),
                ChainCase(cResult = DirectiveAwareChainDenial(), readC = true, bypassC = true),
            )
        cases.forEach { case ->
            val fixture = chainWorld(case)
            val world = fixture.world.assumptions

            val result =
                coroutineResolverSubject.resolve(
                    SharedOperationContext.create(world),
                    world.operationSelectionsFrom("{ item { a } }"),
                )
            val item = result.chainObjectValue(world, "Query", "item")
            val bValue = item.chainCell(world, "Item", "b").getValue().get()
            val aCheckerResult = item.chainCell(world, "Item", "a").getFieldCheckerResult()

            if (case.readC && case.cResult is ChainDenial && !case.bypassC) {
                assertSame(case.cResult.error, assertIs<ErrorEngineResult>(bValue).errorData.cause)
                val failure = assertFailsWith<Exception> { aCheckerResult.get() }
                assertTrue(failure.chainCauses().contains(case.cResult.error))
            } else {
                assertEquals(42, bValue)
                assertSame(CheckerResult.Success, aCheckerResult.get())
            }
            assertEquals(41, item.chainCell(world, "Item", "c").getValue().get())
            assertSame(case.cResult, item.chainCell(world, "Item", "c").getFieldCheckerResult().get())
            assertEquals(1, fixture.aCheckerCalls.get())
            assertEquals(1, fixture.bResolverCalls.get())
            assertEquals(0, fixture.bCheckerCalls.get())
            assertEquals(1, fixture.cCheckerCalls.get())
        }
    }

    @Test
    fun `overlapping raw named pairs and ordinary demand execute each producer once`() {
        val fixture =
            chainWorld(
                ChainCase(
                    cResult = CheckerResult.Success,
                    readC = true,
                    bypassC = false,
                ),
            )
        val world = fixture.world.assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world),
                world.operationSelectionsFrom("{ item { a b c } }"),
            )
        val item = result.chainObjectValue(world, "Query", "item")

        assertEquals(42, item.chainCell(world, "Item", "b").getValue().get())
        assertEquals(1, fixture.aCheckerCalls.get())
        assertEquals(1, fixture.bResolverCalls.get())
        assertEquals(1, fixture.bCheckerCalls.get())
        assertEquals(1, fixture.cCheckerCalls.get())
    }

    @Test
    fun `checker object input follows parent backedges raw`() {
        val auditSeen = AtomicInteger()
        val world =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      box: Box! @resolver(result: {audit: 9, children: [{protected: 1}]})
                    }

                    type Box {
                      audit: Int!
                      children: [Child!]!
                    }

                    type Child {
                      parent: Box! @parent
                      protected: Int!
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    val protected = schema.requireObjectField("Child", "protected")
                    mapOf(
                        protected to
                            FieldChecker.of(
                                protected,
                                schema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    mapOf(
                                        "input" to
                                            ResolverFragmentTemplates(
                                                objectFragmentTemplate =
                                                    schema
                                                        .fragmentFrom(
                                                            "fragment Input on Child { parent { audit } }",
                                                        ).materializeSelections,
                                                queryFragmentTemplate = materializeSelectionForestOf(),
                                            ),
                                    ),
                            ) { _, inputs, _ ->
                                val parent =
                                    assertIs<EngineObjectData.Sync>(
                                        inputs.getValue("input").objectValue.get("parent"),
                                    )
                                auditSeen.set(parent.get("audit") as Int)
                                CheckerResult.Success
                            },
                    )
                },
            ).assumptions

        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world),
            world.operationSelectionsFrom("{ box { children { protected } } }"),
        )

        assertEquals(9, auditSeen.get())
    }

    private fun chainWorld(case: ChainCase): ChainFixture {
        val aCheckerCalls = AtomicInteger()
        val bResolverCalls = AtomicInteger()
        val bCheckerCalls = AtomicInteger()
        val cCheckerCalls = AtomicInteger()
        val world =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    directive @bypassPolicyCheck on FIELD

                    type Query {
                      item: Item!
                    }

                    type Item {
                      a: Int!
                      b: Int!
                      c: Int!
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldResolvers = { schema ->
                    val bFragment =
                        schema.fragmentFrom(
                            if (case.bypassC) {
                                "fragment Input on Item { c @bypassPolicyCheck }"
                            } else {
                                "fragment Input on Item { c }"
                            },
                        )
                    mapOf(
                        schema.requireObjectField("Query", "item") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Item") {
                                    "a" setTo 7
                                    "c" setTo 41
                                }
                            },
                        schema.requireObjectField("Item", "b") to
                            fieldResolverOf(bFragment) { input, _ ->
                                bResolverCalls.incrementAndGet()
                                if (case.readC) {
                                    (input.get("c") as Int) + 1
                                } else {
                                    42
                                }
                            },
                    )
                },
                fieldCheckers = { schema ->
                    val a = schema.requireObjectField("Item", "a")
                    val b = schema.requireObjectField("Item", "b")
                    val c = schema.requireObjectField("Item", "c")
                    fun bInput(alias: String): ResolverFragmentTemplates =
                        ResolverFragmentTemplates(
                            objectFragmentTemplate =
                                schema
                                    .fragmentFrom("fragment Input on Item { $alias: b }")
                                    .materializeSelections,
                            queryFragmentTemplate = materializeSelectionForestOf(),
                        )
                    mapOf(
                        a to
                            FieldChecker.of(
                                a,
                                schema.requireQueryTypeDef(),
                                fragmentTemplates =
                                    linkedMapOf(
                                        "first" to bInput("firstB"),
                                        "second" to bInput("secondB"),
                                    ),
                            ) { _, inputs, _ ->
                                aCheckerCalls.incrementAndGet()
                                assertEquals(42, inputs.getValue("first").objectValue.get("firstB"))
                                assertEquals(42, inputs.getValue("second").objectValue.get("secondB"))
                                CheckerResult.Success
                            },
                        b to
                            FieldChecker.of(b, schema.requireQueryTypeDef()) { _, _, _ ->
                                bCheckerCalls.incrementAndGet()
                                CheckerResult.Success
                            },
                        c to
                            FieldChecker.of(c, schema.requireQueryTypeDef()) { _, _, _ ->
                                cCheckerCalls.incrementAndGet()
                                case.cResult
                            },
                    )
                },
            )
        return ChainFixture(
            world = world,
            aCheckerCalls = aCheckerCalls,
            bResolverCalls = bResolverCalls,
            bCheckerCalls = bCheckerCalls,
            cCheckerCalls = cCheckerCalls,
        )
    }
}

private data class ChainCase(
    val cResult: CheckerResult,
    val readC: Boolean,
    val bypassC: Boolean,
)

private data class ChainFixture(
    val world: TestWorld,
    val aCheckerCalls: AtomicInteger,
    val bResolverCalls: AtomicInteger,
    val bCheckerCalls: AtomicInteger,
    val cCheckerCalls: AtomicInteger,
)

private open class ChainDenial(message: String) : CheckerResult.Error {
    override val error: Exception = IllegalStateException(message)

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}

private class DirectiveAwareChainDenial : ChainDenial("directive-aware denial") {
    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean =
        !checkNotNull(ctx.fieldDirectives).hasDirective("bypassPolicyCheck") { it.isEmpty() }
}

private fun ObjectEngineResult.chainCell(
    world: model.Assumptions,
    typeName: String,
    fieldName: String,
): EngineResultCell =
    getCell(
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField(typeName, fieldName),
            emptyMap(),
        ),
    )

private fun ObjectEngineResult.chainObjectValue(
    world: model.Assumptions,
    typeName: String,
    fieldName: String,
): ObjectEngineResult = assertIs(chainCell(world, typeName, fieldName).getValue().get())

private fun Throwable.chainCauses(): Set<Throwable> =
    generateSequence(this) { it.cause }.toSet()
