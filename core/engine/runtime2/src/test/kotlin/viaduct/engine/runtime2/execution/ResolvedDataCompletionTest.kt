package viaduct.engine.runtime2.execution

import graphql.GraphQL
import graphql.GraphQLContext
import graphql.GraphqlErrorBuilder
import graphql.SerializationError
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.language.Field
import graphql.schema.Coercing
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.GraphQLScalarType
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.MockExecutorCodeInjector
import viaduct.engine.api.mocks.createSchema
import viaduct.engine.api.mocks.createSchemaWithWiring
import viaduct.engine.runtime2.Engine2
import viaduct.engine.runtime2.execution.ScalarFeatureTest.ScalarCase
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineObjectDataEntry
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.materializeSelectionsFrom
import viaduct.graphql.schema.ViaductSchema
import viaduct.service.api.SchemaId
import viaduct.service.api.spi.FlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct

class ResolvedDataCompletionTest {
    private val sdl = "extend type Query { item: Item @resolver } enum Status { ACTIVE } type Item { name: String count: Int! children: [Item!] child: Item status: Status names: [String] }"
    private val schemas = ViaductAndGJSchema.fromGraphQLSchema(
        createSchema(sdl).schema
    )
    private val item = schemas.loweredSchema.requireType("Item") as ViaductSchema.Object
    private val definition = schemas.graphQLSchema.queryType.getFieldDefinition("item")
    private val field = MergedField.newMergedField(Field.newField("item").build()).build()
    private val environment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
        .locale(Locale.ROOT)
        .graphQLSchema(schemas.graphQLSchema)
        .fieldDefinition(definition)
        .fieldType(definition.type)
        .mergedField(field)
        .executionStepInfo(
            ExecutionStepInfo.newExecutionStepInfo()
                .type(definition.type).fieldDefinition(definition).field(field)
                .path(ResultPath.rootPath().segment("item")).build()
        )
        .build()

    @Test
    fun `completes enum typename and nullable values`() =
        runTest {
            val data = ResolvedEngineObjectData(
                schemas.graphQLSchema.getObjectType("Item"),
                mapOf("__typename" to "ignored", "status" to "ACTIVE", "name" to null, "names" to listOf("one", null)),
            )

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertEquals(mapOf("__typename" to "Item", "status" to "ACTIVE", "name" to null, "names" to listOf("one", null)), result.getData())
            assertEquals(emptyList<Any>(), result.errors)
        }

    @Test
    fun `nullable object contains a nonnull failure without nulling siblings`() =
        runTest {
            val child = ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("Item"), mapOf("count" to null))
            val data = ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("Item"), mapOf("name" to "parent", "child" to child))
            val reference = GraphQL.newGraphQL(schemas.graphQLSchema).build()
                .execute {
                    it.query("{ item { name child { count } } }")
                        .root(mapOf("item" to mapOf("name" to "parent", "child" to mapOf("count" to null))))
                }

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertEquals(mapOf("name" to "parent", "child" to null), result.getData())
            assertEquals(listOf("item", "child", "count"), result.errors.single().path)
            assertEquals(reference.errors.single().message, result.errors.single().message)
        }

    @Suppress("DEPRECATION")
    @Test
    fun `engine entry point uses its configured handler without executing resolvers`() =
        runTest {
            val failure = IllegalStateException("private failure")
            val module = EngineTestModule(sdl) {
                field("Query" to "item") {
                    resolver { fn { _, _, _, _, _ -> error("Completion must not execute a resolver") } }
                }
            }
            val handler = DataFetcherExceptionHandler { parameters ->
                assertEquals(failure, parameters.exception)
                CompletableFuture.completedFuture(
                    DataFetcherExceptionHandlerResult.newResult().error(
                        GraphqlErrorBuilder.newError(parameters.dataFetchingEnvironment).message("configured handler").build(),
                    ).build(),
                )
            }
            val viaduct = StandardViaduct.Builder()
                .withSchemaConfiguration(SchemaConfiguration.fromSdl(sdl))
                .withTenantModuleInjectorFactory(MockExecutorCodeInjector(module.mockExecutorRegistry))
                .withExecutorRegistryConfigSources(listOf(module.toModuleConfigSource()))
                .withDataFetcherExceptionHandler(handler)
                .withFlagManager(object : FlagManager {
                    override fun isEnabled(flag: FlagManager.Flag): Boolean = flag == FlagManager.Flags.ENGINE2_ENABLED
                })
                .build()
            val engine = assertInstanceOf(Engine2::class.java, viaduct.engineRegistry.getEngine(SchemaId.Base))
            val data = engineObjectDataOf(item, mapOf("name" to EngineErrorData.of(failure)))

            val result = engine.completeResolvedData(data, environment)

            assertEquals(mapOf("name" to null), result.getData())
            assertEquals("configured handler", result.errors.single().message)
            assertEquals(listOf("item", "name"), result.errors.single().path)
        }

    @Test
    fun `completes aliases and nested lists without executing any fields`() =
        runTest {
            val child = engineObjectDataOf(
                item,
                listOf(
                    EngineObjectDataEntry.of("label", schemas.loweredSchema.requireObjectField("Item", "name"), "child"),
                )
            )
            val data = engineObjectDataOf(item, mapOf("count" to 3, "children" to listOf(child)))

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertEquals(mapOf("count" to 3, "children" to listOf(mapOf("label" to "child"))), result.getData())
            assertEquals(emptyList<Any>(), result.errors)
        }

    @Test
    fun `awaits configured error handler and preserves nested alias paths`() =
        runTest {
            val failure = IllegalStateException("denied input")
            val child = engineObjectDataOf(
                item,
                listOf(
                    EngineObjectDataEntry.of("label", schemas.loweredSchema.requireObjectField("Item", "name"), EngineErrorData.of(failure)),
                )
            )
            val data = engineObjectDataOf(item, mapOf("children" to listOf(child)))
            val handler = DataFetcherExceptionHandler { parameters ->
                assertEquals(failure, parameters.exception)
                assertEquals("name", parameters.dataFetchingEnvironment.field.name)
                assertEquals("label", parameters.dataFetchingEnvironment.field.resultKey)
                CompletableFuture.supplyAsync {
                    DataFetcherExceptionHandlerResult.newResult().error(
                        GraphqlErrorBuilder.newError(parameters.dataFetchingEnvironment).message("formatted input error").build()
                    ).build()
                }
            }

            val result = completeResolvedData(data, environment, handler)

            assertEquals(mapOf("children" to listOf(mapOf("label" to null))), result.getData())
            assertEquals("formatted input error", result.errors.single().message)
            assertEquals(listOf("item", "children", 0, "label"), result.errors.single().path)
        }

    @Test
    fun `nonnull input errors bubble to the nullable list`() =
        runTest {
            val child = engineObjectDataOf(item, mapOf("count" to EngineErrorData.of(IllegalStateException("missing count"))))
            val data = engineObjectDataOf(item, mapOf("children" to listOf(child)))

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertEquals(mapOf("children" to null), result.getData())
            assertEquals(listOf("item", "children", 0, "count"), result.errors.single().path)
        }

    @Test
    fun `nonnull input errors bubble through the checker root`() =
        runTest {
            val data = engineObjectDataOf(item, mapOf("count" to EngineErrorData.of(IllegalStateException("missing count"))))

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertNull(result.getData<Any?>())
            assertEquals(listOf("item", "count"), result.errors.single().path)
        }

    @Test
    fun `reports scalar serialization failure before nonnull bubbling`() =
        runTest {
            val data = ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("Item"), mapOf("count" to "invalid"))

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertNull(result.getData<Any?>())
            assertInstanceOf(SerializationError::class.java, result.errors.single())
            assertEquals(listOf("item", "count"), result.errors.single().path)
        }

    @Test
    fun `reports a null required field`() =
        runTest {
            val data = ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("Item"), mapOf("count" to null))

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertNull(result.getData<Any?>())
            assertEquals("NullValueInNonNullableField", result.errors.single().errorType.toString())
            assertEquals(listOf("item", "count"), result.errors.single().path)
        }

    @Test
    fun `a throwing handler preserves nullable list elements and indexed error paths`() =
        runTest {
            val failure = IllegalArgumentException("resolution failed")
            val handlerFailure = IllegalStateException("handler failed")
            val data = engineObjectDataOf(item, mapOf("names" to listOf("before", EngineErrorData.of(failure), "after")))
            var calls = 0
            val handler = DataFetcherExceptionHandler { parameters ->
                calls++
                assertSame(failure, parameters.exception)
                assertEquals(listOf("item", "names", 1), parameters.path.toList())
                throw handlerFailure
            }

            val result = completeResolvedData(data, environment, handler)

            assertEquals(mapOf("names" to listOf("before", null, "after")), result.getData())
            assertEquals(1, calls)
            assertEquals(listOf("item", "names", 1), result.errors.single().path)
            assertEquals("Exception while fetching data (/item/names[1]) : handler failed", result.errors.single().message)
        }

    @Test
    fun `a nonnull failure retains errors from other resolved fields like GraphQL Java`() =
        runTest {
            val values = linkedMapOf("count" to "invalid", "status" to "UNKNOWN")
            val data = ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("Item"), values)
            val reference = GraphQL.newGraphQL(schemas.graphQLSchema).build()
                .execute { it.query("{ item { count status } }").root(mapOf("item" to values)) }

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertNull(result.getData<Any?>())
            assertEquals(setOf(listOf("item", "count"), listOf("item", "status")), reference.errors.map { it.path }.toSet())
            assertEquals(reference.errors.map { it.path }, result.errors.map { it.path })
            assertTrue(result.errors.all { it is SerializationError })
        }

    @Test
    fun `a nonnull list element retains errors from later resolved elements like GraphQL Java`() =
        runTest {
            val children = listOf(mapOf("count" to "invalid"), mapOf("count" to "also invalid"))
            val data = ResolvedEngineObjectData(
                schemas.graphQLSchema.getObjectType("Item"),
                mapOf("children" to children.map { ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("Item"), it) }),
            )
            val reference = GraphQL.newGraphQL(schemas.graphQLSchema).build()
                .execute { it.query("{ item { children { count } } }").root(mapOf("item" to mapOf("children" to children))) }

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertEquals(mapOf("children" to null), result.getData())
            assertEquals(2, reference.errors.size)
            assertEquals(reference.errors.map { it.path }, result.errors.map { it.path })
        }

    @ParameterizedTest
    @ValueSource(strings = ["[Int]", "[Int!]", "[Int]!", "[Int!]!"])
    fun `list nullability determines where stored element errors stop`(listType: String) =
        runTest {
            val localSchemas = ViaductAndGJSchema.fromGraphQLSchema(
                createSchema("extend type Query { item: Item } type Item { values: $listType name: String }").schema,
            )
            val localItem = localSchemas.loweredSchema.requireType("Item") as ViaductSchema.Object
            val data = engineObjectDataOf(
                localItem,
                listOf(
                    EngineObjectDataEntry.of(
                        "aliased",
                        localSchemas.loweredSchema.requireObjectField("Item", "values"),
                        listOf(1, EngineErrorData.of(IllegalArgumentException("failed element")), 3),
                    ),
                    EngineObjectDataEntry.of("name", localSchemas.loweredSchema.requireObjectField("Item", "name"), "healthy"),
                ),
            )
            val localEnvironment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(environment)
                .graphQLSchema(localSchemas.graphQLSchema).build()

            val result = completeResolvedData(data, localEnvironment, SimpleDataFetcherExceptionHandler())

            val expected = when (listType) {
                "[Int!]!" -> null
                "[Int!]" -> mapOf("aliased" to null, "name" to "healthy")
                else -> mapOf("aliased" to listOf(1, null, 3), "name" to "healthy")
            }
            assertEquals(expected, result.getData())
            assertEquals(listOf("item", "aliased", 1), result.errors.single().path)
        }

    @ParameterizedTest
    @ValueSource(strings = ["[Int]", "[Int!]", "[Int]!", "[Int!]!", "[[Int!]!]"])
    fun `null list elements produce the same data and errors as GraphQL Java`(listType: String) =
        runTest {
            val schema = createSchema("extend type Query { item: Item } type Item { values: $listType }").schema
            val values = if (listType.startsWith("[[")) listOf(listOf(1, null)) else listOf(1, null, 3)
            val fields = mapOf("values" to values)
            val data = ResolvedEngineObjectData(schema.getObjectType("Item"), fields)
            val reference = GraphQL.newGraphQL(schema).build()
                .execute { it.query("{ item { values } }").root(mapOf("item" to fields)) }

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertEquals(reference.getData<Map<String, Any?>>()?.get("item"), result.getData())
            assertEquals(reference.errors.map { it.path }, result.errors.map { it.path })
            assertEquals(reference.errors.map { it.message }, result.errors.map { it.message })
        }

    @Test
    fun `nested list serialization failures stop at the nearest nullable list`() =
        runTest {
            val schema = createSchema("extend type Query { item: Item } type Item { values: [[Int!]] }").schema
            val data = ResolvedEngineObjectData(
                schema.getObjectType("Item"),
                mapOf("values" to listOf(listOf(1, "invalid"), listOf(2), null, emptyList<Int>())),
            )

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertEquals(mapOf("values" to listOf(null, listOf(2), null, emptyList<Int>())), result.getData())
            assertInstanceOf(SerializationError::class.java, result.errors.single())
            assertEquals(listOf("item", "values", 0, 1), result.errors.single().path)
        }

    @Test
    fun `empty selections and null or empty lists do not load unselected fields`() =
        runTest {
            val empty = engineObjectDataOf(item, emptyMap())
            val data = engineObjectDataOf(item, mapOf("names" to null, "children" to emptyList<Any>()))

            val emptyResult = completeResolvedData(empty, environment, SimpleDataFetcherExceptionHandler())
            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertEquals(emptyMap<String, Any?>(), emptyResult.getData())
            assertTrue(emptyResult.errors.isEmpty())
            assertEquals(mapOf("names" to null, "children" to emptyList<Any>()), result.getData())
            assertTrue(result.errors.isEmpty())
        }

    @ParameterizedTest(name = "{0} resolved output")
    @MethodSource("viaduct.engine.runtime2.execution.ScalarFeatureTest#scalars")
    fun `serializes every supported output scalar without changing the resolved input`(scalar: ScalarCase) =
        runTest {
            val schema = createSchemaWithWiring("extend type Query { item: Item } type Item { value: ${scalar.name} }").schema
            val data = ResolvedEngineObjectData(schema.getObjectType("Item"), mapOf("value" to scalar.output))

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertEquals(mapOf("value" to scalar.serialized), result.getData())
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertSame(scalar.output, data.get("value"))
        }

    @Test
    fun `custom scalar serialization receives the request context and locale`() =
        runTest {
            val scalar = GraphQLScalarType.newScalar().name("Localized")
                .coercing(object : Coercing<Any, String> {
                    override fun serialize(
                        dataFetcherResult: Any,
                        graphQLContext: GraphQLContext,
                        locale: Locale
                    ): String = "${graphQLContext.get<String>("prefix")}${dataFetcherResult.toString().uppercase(locale)}"
                }).build()
            val type = schemas.graphQLSchema.getObjectType("Item").transform {
                it.field { field -> field.name("localized").type(scalar) }
            }
            val data = ResolvedEngineObjectData(type, mapOf("localized" to "i"))
            val localizedEnvironment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(environment)
                .graphQLContext(GraphQLContext.newContext().of("prefix", "result:").build())
                .locale(Locale.forLanguageTag("tr")).build()

            val result = completeResolvedData(data, localizedEnvironment, SimpleDataFetcherExceptionHandler())

            assertEquals(mapOf("localized" to "result:İ"), result.getData())
            assertTrue(result.errors.isEmpty())
        }

    @Test
    fun `JSON preserves an opaque engine object instead of recursively completing it`() =
        runTest {
            val schema = createSchemaWithWiring("extend type Query { item: Item } type Item { value: JSON }").schema
            val opaque = ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("Item"), mapOf("count" to null))
            val data = ResolvedEngineObjectData(schema.getObjectType("Item"), mapOf("value" to opaque))

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            assertSame(opaque, result.getData<Map<String, Any?>>()?.get("value"))
            assertTrue(result.errors.isEmpty())
        }

    @ParameterizedTest
    @MethodSource("enumValues")
    fun `enum completion validates strings and Java enums against the runtime schema`(
        value: Any?,
        expected: String?,
        invalid: Boolean
    ) = runTest {
        val data = ResolvedEngineObjectData(schemas.graphQLSchema.getObjectType("Item"), mapOf("status" to value, "name" to "healthy"))

        val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

        assertEquals(mapOf("status" to expected, "name" to "healthy"), result.getData())
        if (invalid) {
            assertInstanceOf(SerializationError::class.java, result.errors.single())
            assertEquals(listOf("item", "status"), result.errors.single().path)
        } else {
            assertTrue(result.errors.isEmpty())
        }
    }

    @Test
    fun `interface and union fields use the concrete type and preserve aliased typename`() =
        runTest {
            val localSchemas = ViaductAndGJSchema.fromGraphQLSchema(
                createSchema(
                    """
                    extend type Query { item: Item }
                    interface Named { name: String }
                    type Child implements Named { name: String }
                    union Result = Child
                    type Item { named: Named result: Result }
                    """.trimIndent(),
                ).schema,
            )
            val childType = localSchemas.loweredSchema.requireType("Child") as ViaductSchema.Object
            val typename = localSchemas.materializeSelectionsFrom("fragment Input on Child { kind: __typename }")
                .second.single().key.field as ViaductSchema.ObjectField
            val child = engineObjectDataOf(
                childType,
                listOf(
                    EngineObjectDataEntry.of("kind", typename, "ignored"),
                    EngineObjectDataEntry.of("label", localSchemas.loweredSchema.requireObjectField("Child", "name"), "child"),
                ),
            )
            val data = ResolvedEngineObjectData(localSchemas.graphQLSchema.getObjectType("Item"), mapOf("named" to child, "result" to child))

            val result = completeResolvedData(data, environment, SimpleDataFetcherExceptionHandler())

            val expected = mapOf("kind" to "Child", "label" to "child")
            assertEquals(mapOf("named" to expected, "result" to expected), result.getData())
            assertTrue(result.errors.isEmpty())
        }

    @Test
    fun `preserves every error and extension returned by the configured handler`() =
        runTest {
            val data = engineObjectDataOf(item, mapOf("name" to EngineErrorData.of(IllegalStateException("private"))))
            val handler = DataFetcherExceptionHandler { parameters ->
                val first = GraphqlErrorBuilder.newError(parameters.dataFetchingEnvironment)
                    .message("first").extensions(mapOf("code" to "DENIED")).build()
                val second = GraphqlErrorBuilder.newError(parameters.dataFetchingEnvironment).message("second").build()
                CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().errors(listOf(first, second)).build())
            }

            val result = completeResolvedData(data, environment, handler)

            assertEquals(mapOf("name" to null), result.getData())
            assertEquals(listOf("first", "second"), result.errors.map { it.message })
            assertEquals(mapOf("code" to "DENIED"), result.errors.first().extensions)
            assertTrue(result.errors.all { it.path == listOf("item", "name") })
        }

    @Test
    fun `request cancellation interrupts an asynchronous error handler`() =
        runTest {
            val pending = CompletableFuture<DataFetcherExceptionHandlerResult>()
            val handler = DataFetcherExceptionHandler { pending }
            val data = engineObjectDataOf(item, mapOf("name" to EngineErrorData.of(IllegalStateException("failed"))))
            val result = async { completeResolvedData(data, environment, handler) }
            testScheduler.runCurrent()
            assertTrue(!result.isCompleted)

            result.cancelAndJoin()

            assertTrue(result.isCancelled)
            assertTrue(pending.isCancelled)
        }

    private enum class Status {
        ACTIVE,
        UNKNOWN;

        override fun toString(): String = "not the GraphQL name"
    }

    companion object {
        @JvmStatic
        fun enumValues(): Stream<Arguments> =
            Stream.of(
                Arguments.of("ACTIVE", "ACTIVE", false),
                Arguments.of(Status.ACTIVE, "ACTIVE", false),
                Arguments.of(null, null, false),
                Arguments.of("UNKNOWN", null, true),
                Arguments.of(Status.UNKNOWN, null, true),
                Arguments.of(42, null, true),
            )
    }
}
