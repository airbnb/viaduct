package viaduct.engine.runtime2.execution

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.execution.testing.ExecutionTestFixture
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.engineResultOf
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.graphql.schema.ViaductSchema

class QPlanWiringFactoryTest {
    @Test
    fun `fully resolved nested lists convert without dispatching coroutine work`() =
        runTest {
            val sdl = "type Query { values: [[String]] }"
            val world = TestWorld.fromSDL(sdl).assumptions
            val expected = listOf(listOf("before", null, "after"), emptyList(), null)
            val root = world.engineResultOf("Query") { "values" resolvesTo expected }

            val result = fetchValues(sdl, world.schema, root, backgroundScope)

            assertEquals(expected, result)
        }

    @Test
    fun `a pending list element preserves ready siblings and their order`() =
        runTest {
            val sdl = "type Query { values: [String] }"
            val schema = TestWorld.fromSDL(sdl).assumptions.schema
            val field = schema.requireObjectField("Query", "values")
            val list = ListEngineResult.ofPendingValues(field.outputType.unwrapList()!!, 3)
            list[0].value.set("before")
            list[2].value.set("after")
            val root = ObjectEngineResult.of(
                schema.requireQueryTypeDef(),
                mapOf(ObjectEngineResult.GroundKey.of(field, emptyMap()) to list),
            )

            val result = fetchValues(sdl, schema, root, backgroundScope) as CompletableFuture<*>
            testScheduler.runCurrent()
            assertFalse(result.isDone)
            list[1].value.set("middle")

            assertEquals(listOf("before", "middle", "after"), result.await())
        }

    @Test
    fun `ready lists respect request cancellation before conversion`() =
        runTest {
            val sdl = "type Query { values: [String] }"
            val world = TestWorld.fromSDL(sdl).assumptions
            val root = world.engineResultOf("Query") { "values" resolvesTo listOf("ready") }
            val requestJob = Job().apply { cancel() }
            val requestScope = CoroutineScope(coroutineContext + requestJob)

            val result = fetchValues(sdl, world.schema, root, requestScope) as CompletableFuture<*>
            testScheduler.runCurrent()

            val failure = assertThrows<CompletionException> { result.join() }
            assertInstanceOf(CoroutineCancellationBridgeException::class.java, failure.cause)
        }

    @Test
    fun `list objects wait for their type checker before exposing data`() =
        runTest {
            val sdl = "type Query { values: [Item] } type Item { name: String }"
            val world = TestWorld.fromSDL(sdl).assumptions
            val checker = Promise.ofDeferred<CheckerResult?>()
            val item = ObjectEngineResult.of(
                world.schema.requireType("Item") as ViaductSchema.Object,
                values = mapOf(ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Item", "name"), emptyMap()) to "visible"),
                typeCheckerResult = checker,
            )
            val root = world.engineResultOf("Query") { "values" resolvesTo listOf(item) }
            val fixture = ExecutionTestFixture.fromResolvedRoot(sdl, world.schema, root, backgroundScope)

            val result = fixture.runQueryAsync("{ values { name } }")
            testScheduler.runCurrent()
            assertFalse(result.isDone)
            checker.complete(CheckerResult.Success)

            val completed = result.await()
            assertTrue(completed.errors.isEmpty())
            assertEquals(mapOf("values" to listOf(mapOf("name" to "visible"))), completed.getData())
        }

    @Test
    fun `a failed type checker terminates a list with an earlier pending element`() =
        runTest {
            val sdl = "type Query { values: [Item] } type Item { name: String }"
            val schema = TestWorld.fromSDL(sdl).assumptions.schema
            val field = schema.requireObjectField("Query", "values")
            val checker = Promise.ofDeferred<CheckerResult?>()
            val failure = IllegalStateException("checker failed")
            checker.fail(failure)
            val item = ObjectEngineResult.of(schema.requireType("Item") as ViaductSchema.Object, typeCheckerResult = checker)
            val list = ListEngineResult.ofPendingValues(field.outputType.unwrapList()!!, 2)
            list[1].value.set(item)
            val root = ObjectEngineResult.of(
                schema.requireQueryTypeDef(),
                mapOf(ObjectEngineResult.GroundKey.of(field, emptyMap()) to list),
            )

            val result = fetchValues(sdl, schema, root, backgroundScope) as CompletableFuture<*>
            testScheduler.runCurrent()

            val thrown = assertThrows<CompletionException> { result.join() }
            assertSame(failure, thrown.cause)
            assertFalse(list[0].value.isCompleted)
        }

    @Test
    fun `a throwing error handler preserves nullable list element isolation`() =
        runTest {
            val sdl = "type Query { values: [String] }"
            val world = TestWorld.fromSDL(sdl).assumptions
            val resolverFailure = IllegalArgumentException("resolver failed")
            val handlerFailure = IllegalStateException("handler failed")
            val failure = ErrorEngineResult.of(EngineErrorData.of(resolverFailure))
            val root = world.engineResultOf("Query") {
                "values" resolvesTo listOf("before", failure, "after")
            }
            val handlerCalls = mutableListOf<DataFetcherExceptionHandlerParameters>()
            val handler = DataFetcherExceptionHandler { parameters ->
                handlerCalls.add(parameters)
                throw handlerFailure
            }
            val sourceSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl))
            val graphQL = GraphQL.newGraphQL(QPlanWiringFactory(world.schema).wire(sourceSchema))
                .defaultDataFetcherExceptionHandler(handler)
                .build()

            val result = graphQL.executeAsync(
                ExecutionInput.newExecutionInput()
                    .query("{ aliased: values }")
                    .root(QPlanExecutionSource(root, backgroundScope, handler))
                    .build(),
            ).await()

            assertEquals(mapOf("aliased" to listOf("before", null, "after")), result.getData())
            assertEquals(1, handlerCalls.size)
            assertSame(resolverFailure, handlerCalls.single().exception)
            assertEquals(listOf("aliased", 1), handlerCalls.single().path.toList())
            val error = result.errors.single()
            assertEquals(listOf("aliased", 1), error.path)
            assertEquals("Exception while fetching data (/aliased[1]) : handler failed", error.message)
        }

    @Test
    fun `completion source detection requires a request-scoped wrapper`() =
        runTest {
            val schema = TestWorld.fromSDL("type Query { value: String }").assumptions.schema
            val root = ObjectEngineResult.of(schema.requireQueryTypeDef(), emptyMap())

            assertFalse(isQPlanCompletionSource(root))
            assertTrue(isQPlanCompletionSource(QPlanExecutionSource(root, backgroundScope)))
            assertFalse(isQPlanCompletionSource(mapOf("value" to "Classic data")))
            assertFalse(isQPlanCompletionSource(Any()))
            assertFalse(isQPlanCompletionSource(null))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `completion waits for an asynchronous error handler inside nested lists`(initiallyPending: Boolean) =
        runTest {
            val sdl = "type Query { nested: [[String]] }"
            val world = TestWorld.fromSDL(sdl).assumptions
            val failure = ErrorEngineResult.of(EngineErrorData.of(IllegalStateException("nested failure")))
            val resolved = world.engineResultOf("Query") { "nested" resolvesTo listOf(listOf("good", failure)) }
            val key = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "nested"), emptyMap())
            val list = resolved.getCell(key).value.get()
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val cell = root.reserveCell(key)
            cell.setActivated(true)
            cell.fieldCheckerResult.complete(null)
            if (!initiallyPending) root.setCellValue(key, list)
            val pendingHandler = CompletableFuture<DataFetcherExceptionHandlerResult>()
            val handlerParameters = CompletableFuture<DataFetcherExceptionHandlerParameters>()
            var handlerCalls = 0
            val handler = DataFetcherExceptionHandler { parameters ->
                handlerCalls++
                handlerParameters.complete(parameters)
                pendingHandler
            }
            val sourceSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl))
            val graphQL = GraphQL.newGraphQL(QPlanWiringFactory(world.schema).wire(sourceSchema)).build()

            val resultFuture =
                graphQL.executeAsync(
                    ExecutionInput.newExecutionInput()
                        .query("{ aliased: nested }")
                        .root(QPlanExecutionSource(root, backgroundScope, handler))
                        .build(),
                )
            if (initiallyPending) root.setCellValue(key, list)
            val parameters = handlerParameters.await()
            assertEquals(listOf("aliased", 0, 1), parameters.path.toList())
            assertFalse(resultFuture.isDone)

            pendingHandler.complete(
                DataFetcherExceptionHandlerResult.newResult()
                    .error(
                        GraphqlErrorBuilder.newError(parameters.dataFetchingEnvironment)
                            .message("Public nested failure")
                            .build(),
                    ).build(),
            )
            val result = resultFuture.await()

            assertEquals(mapOf("aliased" to listOf(listOf("good", null))), result.getData())
            assertEquals(1, handlerCalls)
            assertEquals("Public nested failure", result.errors.single().message)
            assertEquals(listOf("aliased", 0, 1), result.errors.single().path)
        }

    @Test
    fun `wiring completes values with a request scope`() =
        runTest {
            val registry = SchemaParser().parse("type Query { greeting(prefix: String!): String! }")
            val source = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)
            val schema = ViaductAndGJSchema.fromGraphQLSchema(source).loweredSchema
            val key =
                ObjectEngineResult.GroundKey.of(
                    field = schema.requireObjectField("Query", "greeting"),
                    arguments = mapOf("prefix" to "hello"),
                )
            val root =
                ObjectEngineResult.of(
                    type = schema.requireQueryTypeDef(),
                    values = mapOf(key to "hello world"),
                )
            val executableSchema =
                SchemaGenerator().makeExecutableSchema(
                    registry,
                    RuntimeWiring.newRuntimeWiring().wiringFactory(QPlanWiringFactory(schema)).build(),
                )

            val result =
                GraphQL.newGraphQL(executableSchema).build().executeAsync(
                    ExecutionInput.newExecutionInput()
                        .query("query(${'$'}prefix: String!) { message: greeting(prefix: ${'$'}prefix) }")
                        .variables(mapOf("prefix" to "hello"))
                        .root(QPlanExecutionSource(root, backgroundScope))
                        .build(),
                ).await()

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(mapOf("message" to "hello world"), result.getData())
        }

    @Test
    fun `vanilla GraphQL execution completes a resolved OER tree`() =
        runTest {
            val world = TestWorld.fromSDL(SCHEMA).assumptions
            val friend =
                world.engineResultOf("User") {
                    "id" resolvesTo "user-2"
                    "role" resolvesTo "MEMBER"
                    "tags" resolvesTo emptyList<String>()
                    "friends" resolvesTo emptyList<Any>()
                }
            val user =
                world.engineResultOf("User") {
                    "id".resolvesTo("user-1", fieldCheckerResult = CheckerResult.Success)
                    "role" resolvesTo "ADMIN"
                    "tags" resolvesTo listOf("engineer", null)
                    "friends" resolvesTo listOf(friend)
                }
            val root =
                world.engineResultOf("Query") {
                    field("user", "id" to "user-1").resolvesTo(
                        value = user,
                        fieldCheckerResult = CheckerResult.Success,
                    )
                }
            val fixture =
                ExecutionTestFixture.fromResolvedRoot(
                    schemaSDL = SCHEMA,
                    schema = world.schema,
                    root = root,
                    requestScope = backgroundScope,
                )

            val result =
                fixture.runQueryAsync(
                    """
                query User(${'$'}id: ID!) {
                  account: user(id: ${'$'}id) {
                    id
                    role
                    tags
                    friends {
                      id
                      role
                    }
                  }
                }
                    """.trimIndent(),
                    variables = mapOf("id" to "user-1"),
                ).await()

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(
                mapOf(
                    "account" to
                        mapOf(
                            "id" to "user-1",
                            "role" to "ADMIN",
                            "tags" to listOf("engineer", null),
                            "friends" to
                                listOf(
                                    mapOf(
                                        "id" to "user-2",
                                        "role" to "MEMBER",
                                    ),
                                ),
                        ),
                ),
                result.getData(),
            )
        }

    @Test
    fun `GraphQL completion enforces field and type checker errors`() =
        runTest {
            val world = TestWorld.fromSDL(CHECKER_SCHEMA).assumptions
            val completionOnlyDenial = TestCheckerError("field denied", resolverError = false)
            val typeDenial = TestCheckerError("type denied")
            val listDenial = TestCheckerError("list item denied")
            val protectedType = world.schema.requireType("Protected") as ViaductSchema.Object
            val textKey =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Protected", "text"),
                    emptyMap(),
                )

            fun protected(
                text: String,
                typeCheckerResult: CheckerResult?,
            ): ObjectEngineResult =
                ObjectEngineResult.of(
                    type = protectedType,
                    typeCheckerResult = Promise.of(typeCheckerResult),
                    values = mapOf(textKey to text),
                )
            val listField = world.schema.requireObjectField("Query", "listTypeDenied")
            val list =
                ListEngineResult.of(
                    typeExpr = listField.outputType.unwrapList()!!,
                    values = listOf(protected("visible", null), protected("secret", listDenial)),
                )
            val root =
                world.engineResultOf("Query") {
                    "fieldDenied".resolvesTo("secret", completionOnlyDenial)
                    "typeDenied" resolvesTo protected("secret", typeDenial)
                    "listTypeDenied" resolvesTo list
                }
            val fixture =
                ExecutionTestFixture.fromResolvedRoot(
                    schemaSDL = CHECKER_SCHEMA,
                    schema = world.schema,
                    root = root,
                    requestScope = backgroundScope,
                )

            val result = fixture.runQueryAsync("{ fieldDenied typeDenied { text } listTypeDenied { text } }").await()

            assertEquals(
                mapOf(
                    "fieldDenied" to null,
                    "typeDenied" to null,
                    "listTypeDenied" to listOf(mapOf("text" to "visible"), null),
                ),
                result.getData(),
            )
            assertEquals(
                setOf(
                    listOf("fieldDenied"),
                    listOf("typeDenied"),
                    listOf("listTypeDenied", 1),
                ),
                result.errors.map { error -> error.path }.toSet(),
            )
        }

    @Test
    fun `completion denial does not wait for the raw value`() =
        runTest {
            val world = TestWorld.fromSDL("type Query { value: String }").assumptions
            val key =
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "value"),
                    emptyMap(),
                )
            val denial = TestCheckerError("completion denied")
            val root = ObjectEngineResult.of(world.schema.requireQueryTypeDef(), mutable = true)
            val cell = root.reserveCell(key)
            cell.value
            cell.setActivated(true)
            cell.fieldCheckerResult.complete(denial)
            val fixture =
                ExecutionTestFixture.fromResolvedRoot(
                    schemaSDL = "type Query { value: String }",
                    schema = world.schema,
                    root = root,
                    requestScope = backgroundScope,
                )

            val result = fixture.runQueryAsync("{ value }").await()

            assertEquals(mapOf("value" to null), result.getData())
            assertEquals(listOf("value"), result.errors.single().path)
            assertTrue(result.errors.single().message.contains("completion denied"))
            assertFalse(cell.value.isCompleted)
        }

    @Test
    fun `source Node fields complete directly from resolved node results`() =
        runTest {
            val world = TestWorld.fromSDL(NODE_SCHEMA).assumptions
            val user =
                world.engineResultOf("User") {
                    "id" resolvesTo "user-1"
                    "name" resolvesTo "Ada"
                }
            val root =
                world.engineResultOf("Query") {
                    field("node", "id" to "user-1") resolvesTo user
                }
            val fixture =
                ExecutionTestFixture.fromResolvedRoot(
                    schemaSDL = NODE_SCHEMA,
                    schema = world.schema,
                    root = root,
                    requestScope = backgroundScope,
                )

            val result =
                fixture.runQueryAsync(
                    """
                query {
                  node(id: "user-1") {
                    id
                    ... on User {
                      name
                    }
                  }
                }
                    """.trimIndent(),
                ).await()

            assertTrue(result.errors.isEmpty(), result.errors.joinToString { it.message })
            assertEquals(
                mapOf(
                    "node" to
                        mapOf(
                            "id" to "user-1",
                            "name" to "Ada",
                        ),
                ),
                result.getData(),
            )
        }

    private fun fetchValues(
        sdl: String,
        schema: ViaductSchema,
        root: ObjectEngineResult,
        requestScope: CoroutineScope,
    ): Any? {
        val sourceSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(sdl))
        val executable = QPlanWiringFactory(schema).wire(sourceSchema)
        val field = executable.queryType.getFieldDefinition("values")
        val environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .source(QPlanExecutionSource(root, requestScope))
            .arguments(emptyMap())
            .fieldDefinition(field)
            .executionStepInfo(
                ExecutionStepInfo.newExecutionStepInfo()
                    .type(field.type)
                    .path(ResultPath.rootPath().segment("values"))
                    .build(),
            )
            .build()
        return executable.codeRegistry.getDataFetcher(executable.queryType, field).get(environment)
    }

    private companion object {
        val SCHEMA =
            """
            enum Role {
              ADMIN
              MEMBER
            }

            type Query {
              user(id: ID!, greeting: String = "hello"): User!
            }

            type User {
              id: ID!
              role: Role!
              tags: [String]
              friends: [User!]!
            }
            """.trimIndent()

        val NODE_SCHEMA =
            """
            interface Node {
              id: ID!
            }

            type User implements Node {
              id: ID!
              name: String!
            }

            type Query {
              node(id: ID!): Node
            }
            """.trimIndent()

        val CHECKER_SCHEMA =
            """
            type Query {
              fieldDenied: String
              typeDenied: Protected
              listTypeDenied: [Protected]
            }

            type Protected {
              text: String
            }
            """.trimIndent()
    }
}

private class TestCheckerError(
    message: String,
    private val resolverError: Boolean = true,
) : CheckerResult.Error {
    override val error: Exception = SecurityException(message)

    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = resolverError

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = this
}
