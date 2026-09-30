package semantics.contract

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import model.Arguments
import model.Assumptions
import model.EngineErrorData
import model.ErrorEngineResult
import model.ObjectEngineResult
import model.emptyFragmentOf
import model.fragmentFrom
import model.operationSelectionsFrom
import model.registry.FieldCheckerResolver
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import semantics.shared.ResolverObserver
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext

/** Fragment-free checker enforcement, composed explicitly only by resolver families that enforce it. */
interface FragmentFreeFieldCheckerEnforcementContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `allowed active and passive dependencies reach their consumers`() {
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {passive: 11})
                    }

                    type Item {
                      active: Int! @resolver(result: 7)
                      passive: Int!
                      consumeActive: Int! @resolver(of: "active", result: "sum(active)")
                      consumePassive: Int! @resolver(of: "passive", result: "sum(passive)")
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    listOf("active", "passive").associate { name ->
                        val field = schema.loweredSchema.requireObjectField("Item", name)
                        field to
                            FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                CheckerResult.Success
                            }
                    }
                },
            )
        val world = worldFixture.assumptions

        val item = resolveF2(worldFixture, "{ item { consumeActive consumePassive } }").item(world)

        assertEquals(7, item.value(world, "consumeActive"))
        assertEquals(11, item.value(world, "consumePassive"))
    }

    @Test
    fun `denied active and passive dependencies become consumer input errors`() {
        val denial = EnforcementCheckerError("denied")
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {passive: 11})
                    }

                    type Item {
                      active: Int! @resolver(result: 7)
                      passive: Int!
                      consumeActive: Int! @resolver(of: "active", result: "sum(active)")
                      consumePassive: Int! @resolver(of: "passive", result: "sum(passive)")
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    listOf("active", "passive").associate { name ->
                        val field = schema.loweredSchema.requireObjectField("Item", name)
                        field to FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ -> denial }
                    }
                },
            )
        val world = worldFixture.assumptions

        val item = resolveF2(worldFixture, "{ item { consumeActive consumePassive } }").item(world)

        assertSame(denial.error, assertIs<ErrorEngineResult>(item.value(world, "consumeActive")).errorData.cause)
        assertSame(denial.error, assertIs<ErrorEngineResult>(item.value(world, "consumePassive")).errorData.cause)
    }

    @Test
    fun `unused denied object dependency does not suppress its consumer`() {
        val consumerCalls = AtomicInteger()
        val denial = EnforcementCheckerError("denied")
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      dependency: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ConsumerInput on Query { dependency }",
                                ),
                            ) { _, _ ->
                                consumerCalls.incrementAndGet()
                                42
                            },
                    )
                },
                fieldCheckers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        dependency to
                            FieldCheckerResolver.of(dependency, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                denial
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveF2(testWorld, "{ consumer }")

        assertEquals(42, result.value(world, "consumer", "Query"))
        assertEquals(1, consumerCalls.get())
    }

    @Test
    fun `one checker result is shared by multiple consumers`() {
        val checkerCalls = AtomicInteger()
        val denial = EnforcementCheckerError("denied")
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {denied: 7})
                    }

                    type Item {
                      denied: Int!
                      first: Int! @resolver(of: "denied", result: "sum(denied)")
                      second: Int! @resolver(of: "denied", result: "sum(denied)")
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    val field = schema.loweredSchema.requireObjectField("Item", "denied")
                    mapOf(
                        field to
                            FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                checkerCalls.incrementAndGet()
                                denial
                            },
                    )
                },
            )
        val world = worldFixture.assumptions

        val item = resolveF2(worldFixture, "{ item { first second } }").item(world)

        assertSame(denial.error, assertIs<ErrorEngineResult>(item.value(world, "first")).errorData.cause)
        assertSame(denial.error, assertIs<ErrorEngineResult>(item.value(world, "second")).errorData.cause)
        assertEquals(1, checkerCalls.get())
    }

    @Test
    fun `checker error can define directive-sensitive resolver applicability`() {
        val denial = DirectiveAwareCheckerError()
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    directive @bypassPolicyCheck on FIELD

                    extend type Query {
                      item: Item! @resolver(result: {denied: 7})
                    }

                    type Item {
                      denied: Int!
                      bypassed: Int!
                        @resolver(of: "denied @bypassPolicyCheck", result: "sum(denied)")
                      blocked: Int!
                        @resolver(of: "denied", result: "sum(denied)")
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    val field = schema.loweredSchema.requireObjectField("Item", "denied")
                    mapOf(
                        field to
                            FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                denial
                            },
                    )
                },
            )
        val world = worldFixture.assumptions

        val item = resolveF2(worldFixture, "{ item { bypassed blocked } }").item(world)

        assertEquals(7, item.value(world, "bypassed"))
        assertSame(denial.error, assertIs<ErrorEngineResult>(item.value(world, "blocked")).errorData.cause)
    }

    @Test
    fun `checker denial takes precedence over raw dependency failure`() {
        val rawFailure = IllegalStateException("raw failure")
        val checkerDenial = EnforcementCheckerError("checker denial")
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      dependency: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                EngineErrorData.of(rawFailure)
                            },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ConsumerInput on Query { dependency }",
                                ),
                            ) { input, _ ->
                                input.selectionValues().getValue("dependency")
                            },
                    )
                },
                fieldCheckers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        dependency to
                            FieldCheckerResolver.of(dependency, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                checkerDenial
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveF2(testWorld, "{ consumer }")
        val error = assertIs<ErrorEngineResult>(result.value(world, "consumer", "Query"))

        assertSame(checkerDenial.error, error.errorData.cause)
    }

    @Test
    fun `denial is enforced through aliasing and FromArgument variable instantiation`() {
        val denial = EnforcementCheckerError("denied")
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      dependency(value: Int!): Int!
                      consumer(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ConsumerInput on Query {
                                      aliased: dependency(value: ${'$'}value)
                                    }
                                    """.trimIndent(),
                                ),
                            ) { input, _ ->
                                input.selectionValues().getValue("aliased")
                            },
                    )
                },
                variableProviders = { schema ->
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        Arguments.Variable.of(consumer, "value") to
                            schema.loweredSchema.fromArgument(consumer, "value"),
                    )
                },
                fieldCheckers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        dependency to
                            FieldCheckerResolver.of(dependency, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                denial
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveF2(testWorld, "{ consumer(value: 7) }")

        val error =
            assertIs<ErrorEngineResult>(
                result.value(
                    world,
                    fieldName = "consumer",
                    typeName = "Query",
                    arguments = mapOf("value" to 7),
                ),
            )
        assertSame(
            denial.error,
            error.errorData.cause,
        )
    }

    @Test
    fun `checker failure terminates a waiting consumer with the original failure`() {
        val failure = IllegalStateException("checker failed")
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {dependency: 7})
                    }

                    type Item {
                      dependency: Int!
                      consumer: Int! @resolver(of: "dependency", result: "sum(dependency)")
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                fieldCheckers = { schema ->
                    val field = schema.loweredSchema.requireObjectField("Item", "dependency")
                    mapOf(
                        field to
                            FieldCheckerResolver.of(field, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                throw failure
                            },
                    )
                },
            )
        val world = worldFixture.assumptions

        val item = resolveF2(worldFixture, "{ item { consumer } }").item(world)
        val publishedFailure = assertIs<ErrorEngineResult>(item.value(world, "consumer")).errorData.cause

        assertSame(failure, publishedFailure?.cause)
    }

    @Test
    fun `denial is enforced in a declared Query-fragment resolver input`() {
        val denial = EnforcementCheckerError("query dependency denied")
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      dependency: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ConsumerQuery on Query { dependency }",
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.selectionValues().getValue("dependency")
                            },
                    )
                },
                fieldCheckers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        dependency to
                            FieldCheckerResolver.of(dependency, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                denial
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveF2(testWorld, "{ consumer }")
        val error = assertIs<ErrorEngineResult>(result.value(world, "consumer", "Query"))

        assertSame(denial.error, error.errorData.cause)
    }

    @Test
    fun `unused denied Query dependency does not suppress its consumer`() {
        val consumerCalls = AtomicInteger()
        val denial = EnforcementCheckerError("query dependency denied")
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      dependency: Int!
                      consumer: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    val consumer = schema.loweredSchema.requireObjectField("Query", "consumer")
                    mapOf(
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 7 },
                        consumer to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment ConsumerQuery on Query { dependency }",
                                    ),
                            ) { _, _, _ ->
                                consumerCalls.incrementAndGet()
                                42
                            },
                    )
                },
                fieldCheckers = { schema ->
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        dependency to
                            FieldCheckerResolver.of(dependency, schema.loweredSchema.requireQueryTypeDef()) { _, _, _ ->
                                denial
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveF2(testWorld, "{ consumer }")

        assertEquals(42, result.value(world, "consumer", "Query"))
        assertEquals(1, consumerCalls.get())
    }

    private fun resolveF2(
        world: TestWorld,
        query: String,
        resolverObserver: ResolverObserver = ResolverObserver.NOP,
    ): ObjectEngineResult =
        coroutineResolverSubject.resolve(
            SharedOperationContext.create(world.assumptions, resolverObserver = resolverObserver),
            world.schemas.operationSelectionsFrom(query),
        )
}

private fun ObjectEngineResult.item(world: Assumptions): ObjectEngineResult = assertIs(value(world, "item", "Query"))

private fun ObjectEngineResult.value(
    world: Assumptions,
    fieldName: String,
    typeName: String = "Item",
    arguments: Map<String, Any?> = emptyMap(),
): Any? =
    getCell(
        ObjectEngineResult.GroundKey.of(
            world.schema.requireObjectField(typeName, fieldName),
            arguments,
        ),
    ).value.get()

private class EnforcementCheckerError(message: String) : CheckerResult.Error {
    override val error: Exception = IllegalStateException(message)

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = true

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}

/** The test checker, rather than qplan, assigns meaning to Airbnb's directive spelling. */
private class DirectiveAwareCheckerError : CheckerResult.Error {
    override val error: Exception = IllegalStateException("denied")

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean {
        val directives = checkNotNull(ctx.fieldDirectives)
        return !directives.hasDirective("bypassPolicyCheck") { arguments -> arguments.isEmpty() }
    }

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
