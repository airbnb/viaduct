@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.SelectionSet
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import viaduct.engine.api.ObjectEngineResult.Key
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.derived.DerivedFieldQueryMetadata
import viaduct.engine.api.fragment.Fragment
import viaduct.engine.api.fragment.FragmentFieldEngineResolutionResult
import viaduct.engine.api.fragment.FragmentVariables
import viaduct.engine.api.fragment.ViaductExecutableFragmentParser
import viaduct.engine.api.fragment.fragment
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.mocks.MockFlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class ViaductFragmentLoaderTest {
    @BeforeEach
    fun setup() {
        loader = ViaductFragmentLoader(fragmentTransformer)
    }

    @Test
    fun `test loadFromEngine with nested list of ObjectEngineResults`(): Unit =
        runBlocking {
            val fragment = fragment(
                """
                fragment _ on Query {
                    a {
                        a1
                        a2
                    }
                }
                """
            )

            val metadata = getMockDFQueryMetadata(classPath = "classPath", onRootQuery = true)
            val rootEngineResult = objectEngineResult {
                type = schema.schema.getObjectType("Query")
                data = mapOf(
                    "__typename" to "Query",
                    "a" to listOf(
                        objectEngineResult {
                            type = schema.schema.getObjectType("A")
                            data = mapOf(
                                "__typename" to "A",
                                "a1" to "value for a1",
                                "a2" to "value for a2"
                            )
                        },
                        objectEngineResult {
                            type = schema.schema.getObjectType("A")
                            data = mapOf(
                                "__typename" to "A",
                                "a1" to "value for a1",
                                "a2" to "value for a2"
                            )
                        }
                    )
                )
            }

            val dfe = getMockDFE(
                rootEngineResult = rootEngineResult,
                parentEngineResult = rootEngineResult,
                dfpMetadata = FieldRewriterMetadata(
                    prefix = "_zzz_123456",
                    classPath = "classPath",
                    lateResolvedVariables = mapOf()
                ),
                executionPath = ResultPath.rootPath()
            )

            val document = Document.newDocument().definitions(
                listOf(
                    FragmentDefinition.newFragmentDefinition()
                        .name("_")
                        .selectionSet(
                            SelectionSet.newSelectionSet()
                                .selections(
                                    listOf(
                                        Field.newField("a")
                                            .selectionSet(
                                                SelectionSet.newSelectionSet()
                                                    .selections(
                                                        listOf(
                                                            Field.newField("a1").build(),
                                                            Field.newField("a2").build()
                                                        )
                                                    )
                                                    .build()
                                            )
                                            .build()
                                    )
                                )
                                .build()
                        )
                        .build()
                )
            ).build()

            every { fragmentTransformer.parse(fragment) } returns document

            val result = loader.loadFromEngine(fragment, metadata, null, dfe)

            assertEquals(
                FragmentFieldEngineResolutionResult(
                    data = mapOf(
                        "a" to listOf(
                            mapOf(
                                "a1" to "value for a1",
                                "a2" to "value for a2"
                            ),
                            mapOf(
                                "a1" to "value for a1",
                                "a2" to "value for a2"
                            )
                        )
                    ),
                    errors = emptyList()
                ),
                result
            )
        }

    @Test
    fun `test loadEngineObjectData`(): Unit =
        runBlocking {
            val fragment: Fragment = fragment(
                """
            fragment _ on A {
                a3(a: ${'$'}a, b: ${'$'}b, c: ${'$'}c)
                otherA3: a3(a: ${'$'}a, b: ${'$'}b, c: ${'$'}c)
            }
        """,
                vars = FragmentVariables.fromMap(
                    mapOf(
                        "a" to "value for a",
                        "b" to "value for b",
                        "c" to "value for c"
                    )
                )
            )
            val metadata: DerivedFieldQueryMetadata = getMockDFQueryMetadata(classPath = "classPath", onRootQuery = true)
            val executionPath = ResultPath.fromList(listOf("path"))
            val rootEngineResult: ObjectEngineResultImpl = mockk()
            val parentEngineResult: ObjectEngineResultImpl = rootEngineResult

            val dfe = getMockDFE(
                rootEngineResult = rootEngineResult,
                parentEngineResult = parentEngineResult,
                dfpMetadata = FieldRewriterMetadata(
                    prefix = "_zzz_123456",
                    classPath = "classPath",
                    lateResolvedVariables = mapOf()
                ),
                executionPath = executionPath
            )

            val actualResult = loader.loadEngineObjectData(fragment, metadata, "", dfe)

            assertEquals(rootEngineResult, actualResult)
        }

    @Test
    fun `test loadEngineObjectData with root query`(): Unit =
        runBlocking {
            val fragment = fragment(
                """
        fragment _ on Query {
            field
        }
        """
            )
            val metadata = getMockDFQueryMetadata(classPath = "classPath", onRootQuery = true)
            val rootEngineResult = objectEngineResult {
                type = schema.schema.getObjectType("Query")
                data = mapOf("__typename" to "Query", "field" to "value")
            }
            val dfe = getMockDFE(
                rootEngineResult = rootEngineResult,
                parentEngineResult = rootEngineResult,
                dfpMetadata = FieldRewriterMetadata(
                    prefix = "_zzz_123456",
                    classPath = "classPath",
                    lateResolvedVariables = mapOf()
                ),
                executionPath = ResultPath.rootPath()
            )

            val actualResult = loader.loadEngineObjectData(fragment, metadata, "", dfe)

            assertEquals(rootEngineResult, actualResult)
        }

    @Test
    fun `test loadEngineObjectData with non-root query`(): Unit =
        runBlocking {
            val fragment = fragment(
                """
                fragment _ on A {
                    field
                }
                """
            )
            val metadata = getMockDFQueryMetadata(classPath = "classPath", onRootQuery = false)
            val rootEngineResult = objectEngineResult {
                type = schema.schema.getObjectType("Query")
                data = mapOf(
                    "__typename" to "Query",
                    "a" to objectEngineResult {
                        type = schema.schema.getObjectType("A")
                        data = mapOf("__typename" to "A", "field" to "value")
                    }
                )
            }
            val parentEngineResult = rootEngineResult.dataAtPath(ResultPath.parse("/a")) as ObjectEngineResultImpl
            val dfe = getMockDFE(
                rootEngineResult = rootEngineResult,
                parentEngineResult = parentEngineResult,
                dfpMetadata = FieldRewriterMetadata(
                    prefix = "_zzz_123456",
                    classPath = "classPath",
                    lateResolvedVariables = mapOf()
                ),
                executionPath = ResultPath.parse("/a")
            )

            val actualResult = loader.loadEngineObjectData(fragment, metadata, "", dfe)

            assertEquals(parentEngineResult, actualResult)
        }

    @Test
    fun `test loadEngineObjectData with missing EngineResultLocalContext`(): Unit =
        runBlocking {
            val fragment = fragment(
                """
        fragment _ on A {
            a3(a: ${'$'}a, b: ${'$'}b, c: ${'$'}c)
        }
        """,
                vars = FragmentVariables.fromMap(
                    mapOf(
                        "a" to "value for a",
                        "b" to "value for b",
                        "c" to "value for c"
                    )
                )
            )
            val metadata: DerivedFieldQueryMetadata = getMockDFQueryMetadata(classPath = "classPath", onRootQuery = true)
            val executionPath = ResultPath.fromList(listOf("path"))
            val rootEngineResult: ObjectEngineResultImpl = mockk()
            val parentEngineResult: ObjectEngineResultImpl = rootEngineResult

            val dfe = getMockDFE(
                rootEngineResult = rootEngineResult,
                parentEngineResult = parentEngineResult,
                dfpMetadata = FieldRewriterMetadata(
                    prefix = "_zzz_123456",
                    classPath = "classPath",
                    lateResolvedVariables = mapOf()
                ),
                executionPath = executionPath,
                fieldWithMetadata = null
            )

            val loader = ViaductFragmentLoader(fragmentTransformer)
            assertThrows<UnsupportedOperationException> {
                runBlocking {
                    loader.loadEngineObjectData(fragment, metadata, "", dfe)
                }
            }
        }

    @Test
    fun `test loadEngineObjectData with missing FieldRewriterMetadata`(): Unit =
        runBlocking {
            val fragment = fragment(
                """
        fragment _ on A {
            a3(a: ${'$'}a, b: ${'$'}b, c: ${'$'}c)
        }
        """,
                vars = FragmentVariables.fromMap(
                    mapOf(
                        "a" to "value for a",
                        "b" to "value for b",
                        "c" to "value for c"
                    )
                )
            )
            val metadata: DerivedFieldQueryMetadata = getMockDFQueryMetadata(classPath = "classPath", onRootQuery = true)
            val executionPath = ResultPath.fromList(listOf("path"))
            val rootEngineResult: ObjectEngineResultImpl = mockk()
            val parentEngineResult: ObjectEngineResultImpl = rootEngineResult

            val dfe = getMockDFE(
                rootEngineResult = rootEngineResult,
                parentEngineResult = parentEngineResult,
                dfpMetadata = FieldRewriterMetadata(
                    prefix = "_zzz_123456",
                    classPath = "classPath",
                    lateResolvedVariables = mapOf()
                ),
                executionPath = executionPath,
                localContext = null
            )

            val loader = ViaductFragmentLoader(fragmentTransformer)
            assertThrows<IllegalStateException> {
                runBlocking {
                    loader.loadEngineObjectData(fragment, metadata, "", dfe)
                }
            }
        }

    @Test
    fun `test loadEngineObjectData with null DataFetchingEnvironment`(): Unit =
        runBlocking {
            val fragment = fragment(
                """
                fragment _ on A {
                    field
                }
                """
            )
            val metadata = getMockDFQueryMetadata(classPath = "classPath", onRootQuery = true)
            val source = ""

            // Use a null DataFetchingEnvironment
            val nullDfe: DataFetchingEnvironment? = null

            // We're just testing that a null DataFetchingEnvironment causes a NullPointerException
            // Don't need to check the exact error message since we expect a NPE
            assertThrows<NullPointerException> {
                loader.loadEngineObjectData(fragment, metadata, source, nullDfe!!)
            }
        }

    @Test
    fun `load with simple fragment`() =
        runTest(
            objectEngineResult {
                type = schema.schema.getObjectType("Query")
                data =
                    mapOf(
                        "__typename" to "Query",
                        "a" to
                            objectEngineResult {
                                type = schema.schema.getObjectType("A")
                                data =
                                    mapOf(
                                        "__typename" to "A",
                                        "a4" to "value for a4"
                                    )
                            }
                    )
            },
            executionPath = "/a/a1",
            fragment(
                """
                    fragment _ on A {
                        a4
                    }
                    """
            )
        ) { result ->
            assertEquals(
                FragmentFieldEngineResolutionResult(
                    mapOf(
                        "a4" to "value for a4"
                    ),
                    errors = emptyList()
                ),
                result
            )
        }

    @Test
    fun `load fragment with nested selections`() =
        runTest(
            rootEngineResult = objectEngineResult {
                type = schema.schema.getObjectType("Query")
                data =
                    mapOf(
                        "__typename" to "Query",
                        "b" to
                            objectEngineResult {
                                type = schema.schema.getObjectType("B")
                                data =
                                    mapOf(
                                        "__typename" to "B",
                                        "i4" to
                                            objectEngineResult {
                                                type = schema.schema.getObjectType("C")
                                                data =
                                                    mapOf(
                                                        "__typename" to "C",
                                                        "c1" to "value for c1"
                                                    )
                                            }
                                    )
                            }
                    )
            },
            executionPath = "/b/b1",
            fragment = fragment(
                """
                fragment _ on B {
                    i4 {
                        ...on C {
                            c1
                        }
                        ...on D { # this should be ignored, as the value of `i4` is of type C
                            d1
                        }
                    }
                }
                """
            )
        ) { result ->
            assertEquals(
                FragmentFieldEngineResolutionResult(
                    mapOf(
                        "i4" to
                            mapOf(
                                "c1" to "value for c1"
                            )
                    ),
                    errors = emptyList()
                ),
                result
            )
        }

    @Test
    fun `load fragment with errors`() {
        val a1Error = RuntimeException("error for a1")
        runTest(
            rootEngineResult = objectEngineResult {
                type = schema.schema.getObjectType("Query")
                data =
                    mapOf(
                        "__typename" to "Query",
                        "b" to
                            objectEngineResult {
                                type = schema.schema.getObjectType("B")
                                data =
                                    mapOf(
                                        "__typename" to "B",
                                        "b3" to
                                            objectEngineResult {
                                                type = schema.schema.getObjectType("C")
                                                data =
                                                    mapOf(
                                                        "__typename" to "A",
                                                        "a1" to a1Error
                                                    )
                                            }
                                    )
                            }
                    )
            },
            executionPath = "/b/b1",
            fragment = fragment(
                """
                fragment _ on B {
                    b3 {
                        a1
                    }
                }
                """
            )
        ) { result ->
            val (data, errors) = result
            assertEquals(
                mapOf(
                    "b3" to
                        mapOf(
                            "a1" to null
                        )
                ),
                data
            )
            assertEquals(1, errors.size)
            val error = errors[0]
            assertErrorEquals(a1Error, error.cause)
            assertEquals(a1Error.message, error.graphqlError.message)
            assertEquals(listOf("b3", "a1"), error.graphqlError.path)
        }
    }

    @Test
    fun `load fragment with variables`() =
        runTest(
            rootEngineResult = objectEngineResult {
                type = schema.schema.getObjectType("Query")
                data =
                    mapOf(
                        "__typename" to "Query",
                        "a" to
                            objectEngineResult {
                                type = schema.schema.getObjectType("A")
                                data =
                                    mapOf(
                                        "__typename" to "A",
                                        "_zzz_123456_a3" to "value for a3",
                                        "_zzz_123456_a3_zzz_otherA3" to "value for other a3"
                                    )
                            }
                    )
            },
            executionPath = "/a/a1",
            fragment = fragment(
                """
                fragment _ on A {
                    a3(a: ${'$'}a, b: ${'$'}b, c: ${'$'}c)
                    otherA3: a3(a: ${'$'}a, b: ${'$'}b, c: ${'$'}c)
                }
                """,
                FragmentVariables.fromMap(
                    mapOf(
                        "a" to "value for a",
                        "b" to "value for b",
                        "c" to "value for c"
                    )
                )
            )
        ) { result ->
            assertEquals(
                FragmentFieldEngineResolutionResult(
                    mapOf(
                        "a3" to "value for a3",
                        "otherA3" to "value for other a3"
                    ),
                    errors = emptyList()
                ),
                result
            )
        }

    @Test
    fun `test fragment resolution result to OER conversion`() {
        runBlocking {
            val data =
                mapOf(
                    "__typename" to "A",
                    "a1" to "value for a1", // scalar
                    "a7" to
                        mapOf(
                            "__typename" to "B",
                            "i4" to null, // error
                            "i3" to null // null value
                        ),
                    "a10" to
                        listOf( // nested list
                            listOf(
                                mapOf( // union
                                    "__typename" to "A",
                                    "a6" to true
                                ),
                                mapOf(
                                    "__typename" to "D",
                                    "i3" to null, // error
                                    "d5" to "E2" // enum
                                )
                            )
                        )
                )
            val errors =
                mutableListOf<Pair<String, Throwable>>(
                    Pair("a7.i4.a4", NumberFormatException("error on non-nullable child field")),
                    Pair("a10.0.1.i3", NumberFormatException("error on nested list field"))
                )

            val oer =
                ObjectEngineResultImpl.newFromMap(
                    schema.schema.getObjectType("A"),
                    data,
                    errors,
                    emptyList(),
                    schema,
                    mkRss(
                        "A",
                        """
                        fragment _ on A {
                            __typename
                            a1
                            a7(x: "foo", y: "bar") {
                                __typename
                                i4
                                i3
                            }
                            a10 {
                                ... on A {
                                    __typename
                                    a6
                                },
                                ... on D {
                                    __typename
                                    i3
                                    d5
                                }
                            }
                        }
                        """.trimIndent(),
                        emptyMap(),
                        schema
                    )
                )

            // Test non-error values
            assertEquals("value for a1", oer.fetch(Key("a1"), RAW_VALUE_SLOT))
            assertEquals(null, (oer.fetch(Key("a7", null, mapOf("x" to "foo", "y" to "bar")), RAW_VALUE_SLOT) as ObjectEngineResultImpl).fetch(Key("i3"), RAW_VALUE_SLOT))
            val a10FirstItem = ((oer.fetch(Key("a10"), RAW_VALUE_SLOT) as List<*>)[0] as Cell).fetch(RAW_VALUE_SLOT) as List<*>
            val a10FirstItemFirstItem = (a10FirstItem[0] as Cell).fetch(RAW_VALUE_SLOT) as ObjectEngineResultImpl
            val a10FirstItemSecondItem = (a10FirstItem[1] as Cell).fetch(RAW_VALUE_SLOT) as ObjectEngineResultImpl
            assertEquals(true, a10FirstItemFirstItem.fetch(Key("a6"), RAW_VALUE_SLOT))
            assertEquals("E2", a10FirstItemSecondItem.fetch(Key("d5"), RAW_VALUE_SLOT))

            // Test error values
            assertThrows<RuntimeException> {
                (oer.fetch(Key("a7", null, mapOf("x" to "foo", "y" to "bar")), RAW_VALUE_SLOT) as ObjectEngineResultImpl).fetch(Key("i4"), RAW_VALUE_SLOT)
            }
            assertThrows<NumberFormatException> {
                a10FirstItemSecondItem.fetch(Key("i3"), RAW_VALUE_SLOT)
            }
        }
    }

    @Test
    fun `load fragment with multiple nested selections`() =
        runTest(
            objectEngineResult {
                type = schema.schema.getObjectType("Query")
                data =
                    mapOf(
                        "__typename" to "Query",
                        "c" to objectEngineResult {
                            type = schema.schema.getObjectType("C")
                            data =
                                mapOf(
                                    "__typename" to "C",
                                    "c1" to "value for c1",
                                    "c2" to
                                        objectEngineResult {
                                            type = schema.schema.getObjectType("D")
                                            data =
                                                mapOf(
                                                    "__typename" to "D",
                                                    "d1" to "value for d1"
                                                )
                                        }
                                )
                        }
                    )
            },
            executionPath = "/c/c1",
            fragment(
                """
                fragment _ on C {
                    c1
                    c2 {
                        d1
                    }
                }
                """
            )
        ) { result ->
            assertEquals(
                FragmentFieldEngineResolutionResult(
                    mapOf(
                        "c1" to "value for c1",
                        "c2" to
                            mapOf(
                                "d1" to "value for d1"
                            )
                    ),
                    errors = emptyList()
                ),
                result
            )
        }

    /** Test helper **/
    private fun runTest(
        rootEngineResult: ObjectEngineResultImpl,
        executionPath: String,
        fragment: Fragment,
        dfpClassPath: String = "this.does.not.matter",
        assertions: (FragmentFieldEngineResolutionResult) -> Unit
    ) = runBlocking {
        val loader = getLoaderInstance()
        val metadata =
            getMockDFQueryMetadata(
                classPath = dfpClassPath
            )
        val executionResultPath = ResultPath.parse(executionPath)
        val parentEngineResult = rootEngineResult.dataAtPath(executionResultPath.parent) as? ObjectEngineResultImpl

        // Check if parentEngineResult is null
        if (parentEngineResult == null) {
            fail("parentEngineResult is null for path: ${executionResultPath.parent}")
        }

        val dfe = getMockDFE(
            rootEngineResult = rootEngineResult,
            parentEngineResult = parentEngineResult,
            executionPath = executionResultPath,
            dfpMetadata = FieldRewriterMetadata(
                prefix = "_zzz_123456",
                classPath = dfpClassPath,
                lateResolvedVariables = mapOf()
            )
        )
        val result =
            loader.loadFromEngine(
                fragment,
                metadata,
                mockGeneratedObject,
                dfe
            )
        assertions(result)
    }

    private fun assertErrorEquals(
        expected: Throwable,
        actual: Throwable?
    ) {
        if (actual == null) {
            fail("expected error $expected, but got null")
        }
        assertEquals(expected::class, actual::class)
        assertEquals(expected.message, actual.message)
    }

    /** Mocks **/
    private lateinit var loader: ViaductFragmentLoader
    private val fragmentTransformer: ViaductExecutableFragmentParser = mockk()

    private val schema = ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse(testSchema)))

    private val mockGeneratedObject = mockk<Any>()

    private fun getLoaderInstance() = ViaductFragmentLoader(ViaductExecutableFragmentParser())

    private fun getMockDFE(
        rootEngineResult: ObjectEngineResultImpl,
        parentEngineResult: ObjectEngineResultImpl,
        dfpMetadata: FieldRewriterMetadata,
        executionPath: ResultPath,
        fieldWithMetadata: Optional<FieldWithMetadata>? = Optional.empty(),
        localContext: Optional<CompositeLocalContext>? = Optional.empty()
    ): DataFetchingEnvironment {
        val defaultFieldWithMetadata =
            mockk<FieldWithMetadata> {
                every { metadata } returns listOf(dfpMetadata)
            }
        val executionStepInfo =
            mockk<ExecutionStepInfo> {
                every { path } returns executionPath
            }
        val defaultLocalContext =
            CompositeLocalContext.withContexts(
                EngineResultLocalContext(
                    rootEngineResult = rootEngineResult,
                    parentEngineResult = parentEngineResult,
                    queryEngineResult = rootEngineResult, // only testing queries in this test suite, so queryEngineResult is the same as rootEngineResult
                    executionStrategyParams = mockk(),
                    executionContext = mockk()
                ),
                ContextMocks(
                    myFullSchema = schema,
                    myFlagManager = MockFlagManager.Disabled,
                ).engineExecutionContext
            )
        val mockDFE =
            mockk<DataFetchingEnvironment>(relaxed = true) {
                every { this@mockk.field } returns (fieldWithMetadata?.orElse(defaultFieldWithMetadata))
                every { this@mockk.executionStepInfo } returns executionStepInfo
                every { this@mockk.getLocalContext<CompositeLocalContext?>() } returns (localContext?.orElse(defaultLocalContext))
                every { this@mockk.graphQLSchema } returns schema.schema
            }
        return mockDFE
    }

    private fun getMockDFQueryMetadata(
        classPath: String,
        onRootQuery: Boolean = false,
        onRootMutation: Boolean = false
    ) = mockk<DerivedFieldQueryMetadata>(relaxed = true) {
        every { this@mockk.classPath } returns classPath
        every { this@mockk.onRootQuery } returns onRootQuery
        every { this@mockk.onRootMutation } returns onRootMutation
    }
}
